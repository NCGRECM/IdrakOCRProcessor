package com.dataserve.ocr.test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import com.dataserve.ocr.cmis.IdrakCmisClient;
import com.dataserve.ocr.idrak.IdrakAuthClient;
import com.dataserve.ocr.idrak.IdrakOcrSoapClient;
import com.dataserve.ocr.idrak.IdrakTokenResponse;
import com.dataserve.ocr.idrak.OcrRequestResult;
import com.dataserve.ocr.util.Config;

/**
 * STANDALONE morning smoke test for the Idrak OCR integration - completely separate from
 * TaskScheduler/StarterTask. Run this FIRST, before the real batch touches any real document.
 *
 * Does NOT touch DMS_FILES, OCR_REQUEST, or our own FileNet at all. It runs 4 independent stages
 * and prints PASS/FAIL for each one separately, so a failure at one stage doesn't stop you from
 * also seeing whether a later/earlier stage would have worked.
 *
 * Stages:
 *   [1] Get an OAuth token from the ESB (com.dataserve.ocr.idrak.IdrakAuthClient - same client the
 *       real app uses) - tests network + our client credentials.
 *   [2] Encrypt a small test file with Idrak's public key.
 *       Unlike CryptoUtil (used by OcrUploadTask), this test does not check the certificate's
 *       validity window.
 *   [3] If [2] succeeded: upload the encrypted test file to the client's CMIS (OCRIdark folder).
 *   [4] If [3] succeeded: submit it via the real IdrakOCRFileRequest SOAP call.
 *
 * WARNING: stages 3 and 4, if reached, perform REAL calls against the client's real CMIS
 * repository and real OCR request queue (with a randomly generated test RequestId) - this is not
 * a sandbox. Only run this against config.properties pointed at an environment you're allowed to
 * hit. If you only want stages 1-2 (fully local, zero calls to the client's CMIS/SOAP), pass
 * "--local-only" (stage 1's login still runs, since it doesn't touch CMIS/SOAP either).
 *
 * Build once, then run (uses the same fat jar as the real app - no separate build needed):
 *   mvn package
 *   java -cp target/idrak.ocr.processor-1-jar-with-dependencies.jar com.dataserve.ocr.test.IdrakKeyTest
 *   java -cp target/idrak.ocr.processor-1-jar-with-dependencies.jar com.dataserve.ocr.test.IdrakKeyTest --local-only
 *
 * Optional: pass a real pdf/jpg/png file path as an extra argument to encrypt/upload that instead
 * of the small auto-generated test PDF, e.g.:
 *   java -cp ... com.dataserve.ocr.test.IdrakKeyTest /path/to/sample.pdf
 */
public class IdrakKeyTest {

    private static final String RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_TRANSFORM = "AES/CBC/PKCS5Padding";
    private static final int AES_KEY_BITS = 256;
    private static final int CBC_IV_BYTES = 16;

    // Kept in sync with CryptoUtil.OAEP_SPEC - see CryptoUtil.java's class javadoc.
    private static final OAEPParameterSpec OAEP_SPEC = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    public static void main(String[] args) {
        boolean localOnly = false;
        String filePathArg = null;
        for (String arg : args) {
            if ("--local-only".equalsIgnoreCase(arg)) {
                localOnly = true;
            } else {
                filePathArg = arg;
            }
        }

        System.out.println("=================================================================");
        System.out.println(" Idrak OCR integration - morning smoke test");
        System.out.println(" " + new Date());
        System.out.println("=================================================================");

        // STAGE 1 - login. Independent of everything else below.
        String accessToken = tryGetAccessToken();

        // STAGE 2 - encrypt with Idrak's public key. Certificate validity is intentionally NOT
        // checked here (see class javadoc) - we just use whatever key is in the file.
        File testFile;
        byte[] encrypted;
        try {
            testFile = (filePathArg != null) ? new File(filePathArg) : generateTestPdf();
            System.out.println("\n[2] Encrypting test file for Idrak (" + testFile.getName() + ", "
                    + testFile.length() + " bytes) using their public key (no validity check)...");
            byte[] raw = Files.readAllBytes(testFile.toPath());
            PublicKey idrakPublicKey = loadPublicKeyIgnoringValidity(Config.getIdrakPublicKeyPath());
            encrypted = hybridEncrypt(raw, idrakPublicKey);
            System.out.println("    PASS - encrypted OK, envelope size = " + encrypted.length + " bytes.");
        } catch (Exception e) {
            System.out.println("    FAIL - encryption error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            printFooter();
            return;
        }

        if (localOnly) {
            System.out.println("\n[3]/[4] Skipped (--local-only) - not calling the client's CMIS/SOAP endpoints.");
            printFooter();
            return;
        }
        if (accessToken == null) {
            System.out.println("\n[3]/[4] Skipped - no access token from stage 1, can't call CMIS/SOAP without it.");
            printFooter();
            return;
        }

        // STAGE 3 - real upload to the client's CMIS.
        String clientFileNetId;
        try {
            System.out.println("\n[3] Uploading encrypted test file to client CMIS (OCRIdark folder)...");
            IdrakCmisClient cmisClient = new IdrakCmisClient();
            clientFileNetId = cmisClient.uploadNewDocument(encrypted, testFile.getName(), "application/pdf");
            System.out.println("    PASS - uploaded OK, clientFileNetId = " + clientFileNetId);
        } catch (Exception e) {
            System.out.println("    FAIL - CMIS upload error: " + e.getMessage());
            printFooter();
            return;
        }

        // STAGE 4 - real SOAP submit (creates a real request on the client's side).
        try {
            System.out.println("\n[4] Submitting IdrakOCRFileRequest - NOTE: this creates a REAL request on");
            System.out.println("    the client's side with a randomly generated test RequestId.");
            String requestId = IdrakOcrSoapClient.newRequestId();
            IdrakOcrSoapClient soapClient = new IdrakOcrSoapClient();
            String fileType = IdrakOcrSoapClient.mimeTypeToFileType("application/pdf");
            OcrRequestResult result = soapClient.submitOcrRequest(accessToken, requestId, clientFileNetId, fileType);
            if (result.isSuccess()) {
                System.out.println("    PASS - request accepted: " + result);
                System.out.println("    (This only confirms the request was ACCEPTED - the actual OCR result");
                System.out.println("     only comes back later via the OCR.FileStatusMahfuthat MQ queue.)");
            } else {
                System.out.println("    FAIL - request NOT accepted: " + result);
            }
        } catch (Exception e) {
            System.out.println("    FAIL - SOAP call error: " + e.getMessage());
        }

        printFooter();
    }

    private static String tryGetAccessToken() {
        System.out.println("\n[1] Logging in to get an OAuth access token (ESB/IDM)...");
        try {
            IdrakTokenResponse tokenResponse = new IdrakAuthClient().login();
            String accessToken = tokenResponse.getAuthorizationHeader().replaceFirst("(?i)^bearer\\s+", "");
            System.out.println("    PASS - got a token (length=" + accessToken.length() + ").");
            return accessToken;
        } catch (Exception e) {
            System.out.println("    FAIL - login error: " + e.getMessage());
            System.out.println("    ==> Check network reachability and idrak-client-id/secret in config.properties.");
            return null;
        }
    }

    /** Loads the public key without calling cert.checkValidity() - CryptoUtil.encryptForIdrak still enforces that check in production. */
    private static PublicKey loadPublicKeyIgnoringValidity(String path) throws Exception {
        try (FileInputStream is = openResourceOrFile(path)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
            return cert.getPublicKey();
        }
    }

    /**
     * Same hybrid RSA+AES-CBC envelope as CryptoUtil.hybridEncrypt, duplicated here so this test
     * never depends on CryptoUtil's certificate-validity check. See CryptoUtil's class javadoc.
     */
    private static byte[] hybridEncrypt(byte[] plain, PublicKey rsaPublicKey) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_BITS);
        SecretKey aesKey = keyGen.generateKey();

        byte[] iv = new byte[CBC_IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher aesCipher = Cipher.getInstance(AES_TRANSFORM);
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
        byte[] cipherText = aesCipher.doFinal(plain);

        Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORM);
        rsaCipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey, OAEP_SPEC);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(encryptedAesKey);
        out.write(iv);
        out.write(cipherText);
        return out.toByteArray();
    }

    /**
     * Generates a tiny, spec-valid, single-page PDF (proper xref table + trailer, exact stream
     * length) so the test never depends on an external sample file. Verified against `qpdf --check`
     * with zero warnings.
     */
    private static File generateTestPdf() throws Exception {
        File file = File.createTempFile("idrak-smoke-test-", ".pdf");
        file.deleteOnExit();

        String safeText = ("Idrak OCR smoke test").replace("\\", "").replace("(", "").replace(")", "");
        String content = "BT /F1 12 Tf 20 100 Td (" + safeText + ") Tj ET";
        byte[] contentBytes = content.getBytes("US-ASCII");

        String[] objects = new String[] {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 150] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + contentBytes.length + " >>\nstream\n" + content + "\nendstream"
        };

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write("%PDF-1.4\n".getBytes("US-ASCII"));
        int[] offsets = new int[objects.length + 1];
        for (int i = 0; i < objects.length; i++) {
            offsets[i + 1] = buf.size();
            buf.write(((i + 1) + " 0 obj\n").getBytes("US-ASCII"));
            buf.write(objects[i].getBytes("US-ASCII"));
            buf.write("\nendobj\n".getBytes("US-ASCII"));
        }
        int xrefOffset = buf.size();
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(objects.length + 1).append("\n");
        xref.append("0000000000 65535 f \n");
        for (int i = 1; i <= objects.length; i++) {
            xref.append(String.format("%010d 00000 n \n", offsets[i]));
        }
        buf.write(xref.toString().getBytes("US-ASCII"));
        String trailer = "trailer\n<< /Size " + (objects.length + 1) + " /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF";
        buf.write(trailer.getBytes("US-ASCII"));

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(buf.toByteArray());
        }
        return file;
    }

    private static FileInputStream openResourceOrFile(String path) throws Exception {
        File f = new File(path);
        if (f.exists()) {
            return new FileInputStream(f);
        }
        java.net.URL resource = IdrakKeyTest.class.getClassLoader().getResource(path);
        if (resource == null) {
            throw new java.io.FileNotFoundException("Certificate not found on disk or classpath: " + path);
        }
        return new FileInputStream(resource.getFile());
    }

    private static void printFooter() {
        System.out.println("\n=================================================================");
        System.out.println(" Done.");
        System.out.println("=================================================================");
    }
}
