package com.dataserve.ocr.cmis;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dataserve.ocr.exception.CmisException;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;

/**
 * Client for the CLIENT's own FileNet P8 repository, exposed to us over the CMIS SOAP binding
 * (NOT the native jace.jar/WSI style we use for our own Content Engine elsewhere in this project -
 * this is a separate repository the client hosts, reached only via CMIS ObjectService).
 *
 * Endpoint / repository / folder come from the connection details the client sent us:
 *   URL: cmis-repository-url, RepositoryId: EtimadRepoOS, FolderName: OCRIdark
 *
 * Two operations we actually need:
 *  - uploadNewDocument(): puts our (already-encrypted) outgoing file into the OCRIdark folder as a
 *    brand-new CMIS document, via the standard createDocument operation.
 *  - fetchResultContent(): downloads the (still-encrypted) OCR result the client placed in the same
 *    repository, via getContentStream, by the ResponseFileNetId we got from the MQ notification.
 */
public class IdrakCmisClient {

    private static final String SOAP_ENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String CMISM_NS = "http://docs.oasis-open.org/ns/cmis/messaging/200908/";
    private static final String CMIS_NS = "http://docs.oasis-open.org/ns/cmis/core/200908/";

    /** Uploads the (already encrypted) file as a brand-new document in the client's OCRIdark folder. Returns its CMIS/FileNet object id. */
    public String uploadNewDocument(byte[] encryptedContent, String fileName, String mimeType) throws CmisException {
        String base64Content = Base64.getEncoder().encodeToString(encryptedContent);
        String requestXml =
                "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_ENV_NS + "\" xmlns:cmism=\"" + CMISM_NS + "\" xmlns:cmis=\"" + CMIS_NS + "\">" +
                        "<soapenv:Header/>" +
                        "<soapenv:Body>" +
                        "<cmism:createDocument>" +
                        "<cmism:repositoryId>" + escape(Config.getCmisRepositoryId()) + "</cmism:repositoryId>" +
                        "<cmism:properties>" +
                        "<cmis:properties>" +
                        "<cmis:propertyId propertyDefinitionId=\"cmis:objectTypeId\">" +
                        "<cmis:value>" + escape(Config.getCmisClassId()) + "</cmis:value>" +
                        "</cmis:propertyId>" +
                        "<cmis:propertyString propertyDefinitionId=\"cmis:name\">" +
                        "<cmis:value>" + escape(fileName) + "</cmis:value>" +
                        "</cmis:propertyString>" +
                        "</cmis:properties>" +
                        "</cmism:properties>" +
                        "<cmism:folderId>" + escape(Config.getCmisFolderId()) + "</cmism:folderId>" +
                        "<cmism:contentStream>" +
                        "<cmism:mimeType>" + escape(mimeType) + "</cmism:mimeType>" +
                        "<cmism:filename>" + escape(fileName) + "</cmism:filename>" +
                        "<cmism:stream>" + base64Content + "</cmism:stream>" +
                        "</cmism:contentStream>" +
                        "<cmism:versioningState>major</cmism:versioningState>" +
                        "</cmism:createDocument>" +
                        "</soapenv:Body>" +
                        "</soapenv:Envelope>";

        Log.debug("IdrakCmisClient: uploadNewDocument fileName=" + fileName + " size=" + encryptedContent.length + " bytes");
        String responseXml = post(requestXml, "createDocument");
        String objectId = extractTag(responseXml, "objectId");
        if (objectId == null) {
            throw new CmisException("createDocument response did not contain an objectId. Raw response: " + responseXml);
        }
        Log.debug("IdrakCmisClient: uploaded, objectId=" + objectId);
        return objectId;
    }

    /**
     * Downloads the (still encrypted) content of an object by its CMIS/FileNet object id - used to
     * fetch the OCR result.
     *
     * The server returns getContentStream results using MTOM/XOP, not inline base64: the SOAP
     * envelope's <b:stream> element holds only an <xop:Include href="cid:..."/> reference, and the
     * real bytes are a separate MIME part (Content-Transfer-Encoding: binary) identified by a
     * matching Content-ID header. Reads the raw response bytes and, for a multipart/related
     * Content-Type, parses out the MIME part the XOP reference points to.
     */
    public byte[] fetchResultContent(String objectId) throws CmisException {
        String requestXml =
                "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_ENV_NS + "\" xmlns:cmism=\"" + CMISM_NS + "\">" +
                        "<soapenv:Header/>" +
                        "<soapenv:Body>" +
                        "<cmism:getContentStream>" +
                        "<cmism:repositoryId>" + escape(Config.getCmisRepositoryId()) + "</cmism:repositoryId>" +
                        "<cmism:objectId>" + escape(objectId) + "</cmism:objectId>" +
                        "</cmism:getContentStream>" +
                        "</soapenv:Body>" +
                        "</soapenv:Envelope>";

        Log.debug("IdrakCmisClient: fetchResultContent objectId=" + objectId);
        HttpRawResponse response = postRaw(requestXml, "getContentStream");

        String contentType = response.contentType == null ? "" : response.contentType;
        if (!contentType.toLowerCase().contains("multipart/related")) {
            // Non-MTOM response (e.g. a SOAP fault, or inline content) - handle as plain text.
            String responseText = new String(response.body, StandardCharsets.UTF_8);
            checkForSoapFault(responseText, "getContentStream");
            String inlineBase64 = extractTag(responseText, "stream");
            if (inlineBase64 == null) {
                throw new CmisException("getContentStream response did not contain a stream. Raw response: " + responseText);
            }
            return Base64.getDecoder().decode(inlineBase64.trim());
        }

        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            throw new CmisException("getContentStream returned multipart/related with no boundary parameter. Content-Type: " + contentType);
        }

        MimePart[] parts = splitMultipart(response.body, boundary);
        if (parts.length == 0) {
            throw new CmisException("getContentStream multipart response contained no MIME parts.");
        }

        // Part 1 is always the SOAP/XOP XML envelope - safe to decode as UTF-8 text.
        String soapXml = new String(parts[0].body, StandardCharsets.UTF_8);
        checkForSoapFault(soapXml, "getContentStream");

        String cid = extractXopIncludeCid(soapXml);
        if (cid == null) {
            throw new CmisException("getContentStream response did not contain a <stream> value or an xop:Include reference. Raw response: " + soapXml);
        }

        for (int i = 1; i < parts.length; i++) {
            String partContentId = parts[i].headers.get("content-id");
            if (partContentId != null && stripContentId(partContentId).equals(cid)) {
                // Content-Transfer-Encoding: binary - these are the raw bytes already, no base64 decode.
                return parts[i].body;
            }
        }

        throw new CmisException("getContentStream response referenced MIME part cid:" + cid
                + " via xop:Include, but no matching MIME part was found in the multipart response.");
    }

    private void checkForSoapFault(String responseText, String soapAction) throws CmisException {
        if (responseText.contains("<soapenv:Fault>") || responseText.contains("<soap:Fault>") || responseText.contains("<Fault>")) {
            Log.error("IdrakCmisClient: SOAP fault (" + soapAction + ") response=" + responseText);
            throw new CmisException("CMIS " + soapAction + " returned a SOAP fault: " + responseText);
        }
    }

    /**
     * checkOut + checkIn (matches the client's own SOAP sample) - kept here for the UPDATE case
     * (an existing object), in case that turns out to be needed instead of / in addition to
     * createDocument. NOT currently called from the upload flow - see class-level TODO #1.
     */
    public String checkOutThenCheckIn(String existingObjectId, byte[] encryptedContent, String fileName, String mimeType) throws CmisException {
        String pwcObjectId = checkOut(existingObjectId);
        return checkIn(pwcObjectId, encryptedContent, fileName, mimeType);
    }

    private String checkOut(String objectId) throws CmisException {
        String requestXml =
                "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_ENV_NS + "\" xmlns:cmism=\"" + CMISM_NS + "\">" +
                        "<soapenv:Header/>" +
                        "<soapenv:Body>" +
                        "<cmism:checkOut>" +
                        "<cmism:repositoryId>" + escape(Config.getCmisRepositoryId()) + "</cmism:repositoryId>" +
                        "<cmism:objectId>" + escape(objectId) + "</cmism:objectId>" +
                        "</cmism:checkOut>" +
                        "</soapenv:Body>" +
                        "</soapenv:Envelope>";
        String responseXml = post(requestXml, "checkOut");
        String pwcObjectId = extractTag(responseXml, "objectId");
        if (pwcObjectId == null) {
            throw new CmisException("checkOut response did not contain a PWC objectId. Raw response: " + responseXml);
        }
        return pwcObjectId;
    }

    private String checkIn(String pwcObjectId, byte[] encryptedContent, String fileName, String mimeType) throws CmisException {
        String base64Content = Base64.getEncoder().encodeToString(encryptedContent);
        String requestXml =
                "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_ENV_NS + "\" xmlns:cmism=\"" + CMISM_NS + "\">" +
                        "<soapenv:Header/>" +
                        "<soapenv:Body>" +
                        "<cmism:checkIn>" +
                        "<cmism:repositoryId>" + escape(Config.getCmisRepositoryId()) + "</cmism:repositoryId>" +
                        "<cmism:objectId>" + escape(pwcObjectId) + "</cmism:objectId>" +
                        "<cmism:major>true</cmism:major>" +
                        "<cmism:contentStream>" +
                        "<cmism:mimeType>" + escape(mimeType) + "</cmism:mimeType>" +
                        "<cmism:filename>" + escape(fileName) + "</cmism:filename>" +
                        "<cmism:stream>" + base64Content + "</cmism:stream>" +
                        "</cmism:contentStream>" +
                        "</cmism:checkIn>" +
                        "</soapenv:Body>" +
                        "</soapenv:Envelope>";
        String responseXml = post(requestXml, "checkIn");
        String objectId = extractTag(responseXml, "objectId");
        if (objectId == null) {
            throw new CmisException("checkIn response did not contain an objectId. Raw response: " + responseXml);
        }
        return objectId;
    }

    private String post(String requestXml, String soapAction) throws CmisException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(Config.getCmisRepositoryUrl()).openConnection();
            connection.setRequestMethod("POST");
            // Reuses the idrak-connection-timeout/idrak-read-timeout config values rather than
            // adding new CMIS-specific ones.
            connection.setConnectTimeout(Config.getIdrakConnectionTimeout());
            connection.setReadTimeout(Config.getIdrakReadTimeout());
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/xml; charset=UTF-8");
            connection.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");

            String userPass = Config.getCmisUsername() + ":" + Config.getCmisPassword();
            String basicAuth = Base64.getEncoder().encodeToString(userPass.getBytes("UTF-8"));
            connection.setRequestProperty("Authorization", "Basic " + basicAuth);

            byte[] body = requestXml.getBytes("UTF-8");
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readResponse(statusCode == HttpURLConnection.HTTP_OK
                    ? connection.getInputStream() : connection.getErrorStream());

            if (statusCode != HttpURLConnection.HTTP_OK) {
                Log.error("IdrakCmisClient: HTTP " + statusCode + " (" + soapAction + ") response=" + responseBody);
                throw new CmisException("CMIS " + soapAction + " call failed with HTTP status " + statusCode + ": " + responseBody);
            }
            if (responseBody.contains("<soapenv:Fault>") || responseBody.contains("<soap:Fault>") || responseBody.contains("<Fault>")) {
                Log.error("IdrakCmisClient: SOAP fault (" + soapAction + ") response=" + responseBody);
                throw new CmisException("CMIS " + soapAction + " returned a SOAP fault: " + responseBody);
            }
            return responseBody;
        } catch (IOException e) {
            throw new CmisException("Error calling CMIS " + soapAction, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readResponse(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * Same request/transport as post() above, but returns the response as RAW BYTES (plus the
     * Content-Type header) instead of decoding it as UTF-8 text - needed for getContentStream, whose
     * MTOM/XOP responses carry a binary MIME part that a text reader would corrupt. See
     * fetchResultContent()'s javadoc for the full story.
     */
    private HttpRawResponse postRaw(String requestXml, String soapAction) throws CmisException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(Config.getCmisRepositoryUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(Config.getIdrakConnectionTimeout());
            connection.setReadTimeout(Config.getIdrakReadTimeout());
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/xml; charset=UTF-8");
            connection.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");

            String userPass = Config.getCmisUsername() + ":" + Config.getCmisPassword();
            String basicAuth = Base64.getEncoder().encodeToString(userPass.getBytes("UTF-8"));
            connection.setRequestProperty("Authorization", "Basic " + basicAuth);

            byte[] requestBody = requestXml.getBytes("UTF-8");
            connection.setRequestProperty("Content-Length", String.valueOf(requestBody.length));
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody);
            }

            int statusCode = connection.getResponseCode();
            String contentType = connection.getContentType();
            byte[] responseBytes = readAllBytes(statusCode == HttpURLConnection.HTTP_OK
                    ? connection.getInputStream() : connection.getErrorStream());

            if (statusCode != HttpURLConnection.HTTP_OK) {
                String responseText = new String(responseBytes, StandardCharsets.UTF_8);
                Log.error("IdrakCmisClient: HTTP " + statusCode + " (" + soapAction + ") response=" + responseText);
                throw new CmisException("CMIS " + soapAction + " call failed with HTTP status " + statusCode + ": " + responseText);
            }

            HttpRawResponse result = new HttpRawResponse();
            result.contentType = contentType;
            result.body = responseBytes;
            return result;
        } catch (IOException e) {
            throw new CmisException("Error calling CMIS " + soapAction, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        if (is == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = is.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            is.close();
        }
        return out.toByteArray();
    }

    /** Pulls the boundary="..." (quoted or not) parameter out of a multipart/related Content-Type header. */
    private String extractBoundary(String contentType) {
        Matcher matcher = Pattern.compile("boundary=\"?([^\";]+)\"?").matcher(contentType);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Splits a raw MTOM multipart/related HTTP body into its MIME parts on a byte level (never
     * decoding as text until a part's own headers say it's safe to) - part 0 is always the SOAP/XOP
     * envelope, any further parts are the XOP-attached binary content.
     */
    private MimePart[] splitMultipart(byte[] body, String boundary) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        java.util.List<int[]> delimiterRanges = new java.util.ArrayList<>(); // [start, end) of each "--boundary" occurrence
        int searchFrom = 0;
        while (true) {
            int idx = indexOf(body, delimiter, searchFrom);
            if (idx < 0) {
                break;
            }
            delimiterRanges.add(new int[]{idx, idx + delimiter.length});
            searchFrom = idx + delimiter.length;
        }

        java.util.List<MimePart> parts = new java.util.ArrayList<>();
        for (int i = 0; i < delimiterRanges.size() - 1; i++) {
            int partStart = delimiterRanges.get(i)[1];
            int partEnd = delimiterRanges.get(i + 1)[0];
            if (partStart >= partEnd) {
                continue;
            }
            MimePart part = parseMimePart(body, partStart, partEnd);
            if (part != null) {
                parts.add(part);
            }
        }
        return parts.toArray(new MimePart[0]);
    }

    /** Parses one MIME part's headers (ASCII, up to the blank line) and raw body bytes (everything after). */
    private MimePart parseMimePart(byte[] body, int start, int end) {
        byte[] headerTerminator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        int headerEnd = indexOf(body, headerTerminator, start);
        int headerTerminatorLen = 4;
        if (headerEnd < 0 || headerEnd > end) {
            // tolerate bare \n\n (some servers normalize line endings)
            headerTerminator = "\n\n".getBytes(StandardCharsets.US_ASCII);
            headerEnd = indexOf(body, headerTerminator, start);
            headerTerminatorLen = 2;
            if (headerEnd < 0 || headerEnd > end) {
                return null;
            }
        }

        String headerText = new String(body, start, headerEnd - start, StandardCharsets.US_ASCII);
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        for (String line : headerText.split("\r\n|\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }

        int bodyStart = headerEnd + headerTerminatorLen;
        int bodyEnd = end;
        // strip the trailing CRLF that precedes the next "--boundary" delimiter
        if (bodyEnd - bodyStart >= 2 && body[bodyEnd - 2] == '\r' && body[bodyEnd - 1] == '\n') {
            bodyEnd -= 2;
        } else if (bodyEnd - bodyStart >= 1 && body[bodyEnd - 1] == '\n') {
            bodyEnd -= 1;
        }
        if (bodyEnd < bodyStart) {
            bodyEnd = bodyStart;
        }

        byte[] partBody = new byte[bodyEnd - bodyStart];
        System.arraycopy(body, bodyStart, partBody, 0, partBody.length);

        MimePart part = new MimePart();
        part.headers = headers;
        part.body = partBody;
        return part;
    }

    /** Plain byte-array search (body can contain arbitrary binary, so no text-based indexOf here). */
    private int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        outer:
        for (int i = Math.max(fromIndex, 0); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Extracts the "xxx" out of <xop:Include ... href="cid:xxx"/> inside a <stream> element. */
    private String extractXopIncludeCid(String xml) {
        Matcher matcher = Pattern.compile("[Hh]ref=\"cid:([^\"]+)\"").matcher(xml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /** A MIME part's Content-ID header looks like "<abc@apache.org>" - strip the angle brackets to compare with an xop:Include's "cid:abc@apache.org". */
    private String stripContentId(String contentId) {
        String trimmed = contentId.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static class HttpRawResponse {
        String contentType;
        byte[] body;
    }

    private static class MimePart {
        java.util.Map<String, String> headers;
        byte[] body;
    }

    private String extractTag(String xml, String tagName) {
        Pattern pattern = Pattern.compile("<(?:\\w+:)?" + tagName + "[^>]*>([^<]*)</(?:\\w+:)?" + tagName + ">");
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
