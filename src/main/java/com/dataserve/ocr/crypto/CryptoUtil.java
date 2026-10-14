package com.dataserve.ocr.crypto;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import com.dataserve.ocr.exception.CmisException;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;

/**
 * Hybrid RSA + AES envelope encryption for the file bytes exchanged with Idrak over CMIS.
 * Envelope: [RSA-OAEP-encrypted AES-256 key][16-byte CBC IV][AES-256/CBC/PKCS7-encrypted bytes],
 * no length prefix - the key portion is split by the RSA modulus size. Requires an explicit
 * OAEPParameterSpec (see OAEP_SPEC) since the transformation name alone defaults MGF1 to SHA-1.
 */
public class CryptoUtil {

    private static final String RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_TRANSFORM = "AES/CBC/PKCS5Padding";
    private static final int AES_KEY_BITS = 256;
    private static final int CBC_IV_BYTES = 16;

    // Main hash SHA-256, MGF1 digest SHA-256, no label - must be passed to Cipher.init() explicitly.
    private static final OAEPParameterSpec OAEP_SPEC = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    /** Encrypts fileBytes for sending TO the client, using Idrak's public key/certificate. */
    public static byte[] encryptForIdrak(byte[] fileBytes) throws CmisException {
        try {
            PublicKey idrakPublicKey = loadPublicKeyFromCertificate(Config.getIdrakPublicKeyPath());
            return hybridEncrypt(fileBytes, idrakPublicKey);
        } catch (Exception e) {
            throw new CmisException("Error encrypting file for Idrak", e);
        }
    }

    /** Decrypts an OCR result we fetched FROM the client, using our own private key. */
    public static byte[] decryptOurResult(byte[] envelope) throws CmisException {
        try {
            PrivateKey ourPrivateKey = loadPrivateKey(Config.getOurPrivateKeyPath());
            return hybridDecrypt(envelope, ourPrivateKey);
        } catch (Exception e) {
            throw new CmisException("Error decrypting OCR result", e);
        }
    }

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

        // No length prefix - encryptedAesKey is always exactly the RSA modulus size, which is how
        // the other side knows where to split it. Order: encrypted key, IV, ciphertext.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(encryptedAesKey);
        out.write(iv);
        out.write(cipherText);
        Log.debug("CryptoUtil: encrypted " + plain.length + " bytes -> envelope of " + out.size() + " bytes");
        return out.toByteArray();
    }

    private static byte[] hybridDecrypt(byte[] envelope, PrivateKey rsaPrivateKey) throws Exception {
        // No length prefix in the envelope - derive the split point from our own key's modulus.
        int rsaKeyBytes = (((RSAPrivateKey) rsaPrivateKey).getModulus().bitLength() + 7) / 8;

        byte[] encryptedAesKey = new byte[rsaKeyBytes];
        System.arraycopy(envelope, 0, encryptedAesKey, 0, rsaKeyBytes);
        byte[] iv = new byte[CBC_IV_BYTES];
        System.arraycopy(envelope, rsaKeyBytes, iv, 0, CBC_IV_BYTES);
        byte[] cipherText = new byte[envelope.length - rsaKeyBytes - CBC_IV_BYTES];
        System.arraycopy(envelope, rsaKeyBytes + CBC_IV_BYTES, cipherText, 0, cipherText.length);

        Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORM);
        rsaCipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey, OAEP_SPEC);
        byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        Cipher aesCipher = Cipher.getInstance(AES_TRANSFORM);
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
        byte[] plain = aesCipher.doFinal(cipherText);
        Log.debug("CryptoUtil: decrypted envelope of " + envelope.length + " bytes -> " + plain.length + " bytes");
        return plain;
    }

    private static PublicKey loadPublicKeyFromCertificate(String path) throws Exception {
        try (InputStream is = openResourceOrFile(path)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
            cert.checkValidity(); // throws CertificateExpiredException if expired - e.g. the current Idrak cert on file
            return cert.getPublicKey();
        }
    }

    private static PrivateKey loadPrivateKey(String path) throws Exception {
        byte[] pem = readAllBytes(path);
        String content = new String(pem, "UTF-8")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(content);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static InputStream openResourceOrFile(String path) throws Exception {
        // path is relative to src/main/resources (e.g. certs/idrak_ocr_public.cer) when run from the
        // exploded classpath, or an absolute path if the deployment copies certs elsewhere.
        java.io.File f = new java.io.File(path);
        if (f.exists()) {
            return new FileInputStream(f);
        }
        // Read the resource via getResourceAsStream - resource.getFile() isn't a real filesystem
        // path when running from inside a jar, so FileInputStream can't open it.
        InputStream resourceStream = CryptoUtil.class.getClassLoader().getResourceAsStream(path);
        if (resourceStream == null) {
            throw new java.io.FileNotFoundException("Certificate/key not found on disk or classpath: " + path);
        }
        return resourceStream;
    }

    private static byte[] readAllBytes(String path) throws Exception {
        try (InputStream is = openResourceOrFile(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
