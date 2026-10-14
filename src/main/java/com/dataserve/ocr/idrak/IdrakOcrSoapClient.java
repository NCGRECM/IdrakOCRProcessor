package com.dataserve.ocr.idrak;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dataserve.ocr.exception.IdrakException;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;

/**
 * SOAP client for the "IdrakOCRFileRequest" web service (SOAP 1.1):
 * - FuncId 53010101: submit an uploaded (encrypted) file by its FileNetId for OCR. Synchronous -
 *   returns only an ack (RequestNum), not the OCR result; the real result arrives via async MQ
 *   notify (see mq.OcrMqConsumer).
 * - FuncId 53010102: poll a previously-submitted request. Only meant for requests with no response
 *   for a long time, not called routinely.
 *
 * ServiceId = "IdrakOCRFileRequest", success StatusCode = "I000000". SCId = "TST" is a
 * best-effort default - see idrak-ocr-scid in config.properties.
 *
 * TODO: the SOAPAction here is only confirmed correct for submitOcrRequest(); checkRequestStatus()
 * reuses it unconfirmed.
 */
public class IdrakOcrSoapClient {

    private static final String NS = "http://www.ejada.com";
    private static final String SOAP_ACTION = "IdrakOCRFileRequest";

    /**
     * OutputFileExtension is NOT the uploaded file's own extension - it's the format Idrak should
     * return the OCR'd TEXT RESULT in. Only "json" or "txt" are valid; "pdf" is not.
     */
    private static final String OUTPUT_FILE_EXTENSION = "txt";

    /** Submits one uploaded (already encrypted, already-in-CMIS) file for OCR processing. */
    public OcrRequestResult submitOcrRequest(String accessToken, String requestId, String clientFileNetId, String fileType)
            throws IdrakException {
        // RqUID = requestId (a GUID), matching the working IdrakOcrService integration's pattern.
        String rqUid = requestId;
        String requestXml =
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ns=\"" + NS + "\">" +
                        "<soapenv:Header/>" +
                        "<soapenv:Body>" +
                        "<ns:IdrakOCRFileRequestRq>" +
                        "<ns:MsgRqHdr>" +
                        "<ns:RqUID>" + escape(rqUid) + "</ns:RqUID>" +
                        "<ns:SCId>" + escape(Config.getIdrakOcrScid()) + "</ns:SCId>" +
                        "<ns:ServiceId>" + escape(Config.getIdrakOcrServiceId()) + "</ns:ServiceId>" +
                        "<ns:FuncId>" + escape(Config.getIdrakOcrFuncIdRequest()) + "</ns:FuncId>" +
                        "</ns:MsgRqHdr>" +
                        "<ns:Body>" +
                        "<ns:SrcSystem>" + escape(Config.getIdrakOcrSrcSystem()) + "</ns:SrcSystem>" +
                        "<ns:RequestId>" + escape(requestId) + "</ns:RequestId>" +
                        "<ns:OutputFileExtension>" + escape(OUTPUT_FILE_EXTENSION) + "</ns:OutputFileExtension>" +
                        "<ns:FileList>" +
                        "<ns:FileInfo>" +
                        "<ns:FileNetId>" + escape(clientFileNetId) + "</ns:FileNetId>" +
                        "<ns:FileType>" + escape(fileType) + "</ns:FileType>" +
                        "</ns:FileInfo>" +
                        "</ns:FileList>" +
                        "</ns:Body>" +
                        "</ns:IdrakOCRFileRequestRq>" +
                        "</soapenv:Body>" +
                        "</soapenv:Envelope>";

        Log.debug("IdrakOcrSoapClient: submitOcrRequest requestId=" + requestId + " clientFileNetId=" + clientFileNetId);
        String responseXml = post(Config.getIdrakOcrRequestUrl(), requestXml, accessToken, rqUid, Config.getIdrakOcrFuncIdRequest());
        return parseResponse(responseXml);
    }

    /**
     * Check Request Status (FuncId 53010102) - only meant for requests that have had no MQ
     * notification for a long time. TODO: confirm the Rq/Rs shape assumed here against a real sample.
     */
    public OcrRequestResult checkRequestStatus(String accessToken, String requestId) throws IdrakException {
        // RqUID must be fresh per call; RequestId stays fixed as the one assigned at submit time
        // (per client feedback - don't reuse requestId as RqUID here like submitOcrRequest() does).
        String rqUid = newRequestId();
        String requestXml =
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ns=\"" + NS + "\">" +
                        "<soapenv:Header/>" +
                        "<soapenv:Body>" +
                        "<ns:IdrakOCRFileRequestRq>" +
                        "<ns:MsgRqHdr>" +
                        "<ns:RqUID>" + escape(rqUid) + "</ns:RqUID>" +
                        "<ns:SCId>" + escape(Config.getIdrakOcrScid()) + "</ns:SCId>" +
                        "<ns:ServiceId>" + escape(Config.getIdrakOcrServiceId()) + "</ns:ServiceId>" +
                        "<ns:FuncId>" + escape(Config.getIdrakOcrFuncIdStatus()) + "</ns:FuncId>" +
                        "</ns:MsgRqHdr>" +
                        "<ns:Body>" +
                        "<ns:SrcSystem>" + escape(Config.getIdrakOcrSrcSystem()) + "</ns:SrcSystem>" +
                        "<ns:RequestId>" + escape(requestId) + "</ns:RequestId>" +
                        "</ns:Body>" +
                        "</ns:IdrakOCRFileRequestRq>" +
                        "</soapenv:Body>" +
                        "</soapenv:Envelope>";

        Log.debug("IdrakOcrSoapClient: checkRequestStatus requestId=" + requestId);
        String responseXml = post(Config.getIdrakOcrStatusUrl(), requestXml, accessToken, rqUid, Config.getIdrakOcrFuncIdStatus());
        return parseResponse(responseXml);
    }

    /**
     * @param rqUid  same RqUID value already placed inside the SOAP body's MsgRqHdr.
     * @param funcId same FuncId value already placed inside the SOAP body's MsgRqHdr (request vs
     *               status endpoints use different FuncId values, so this is passed in per-call).
     *
     *               NEW: also sends RqUID/SCId/ServiceId/FuncId as plain HTTP headers, not just
     *               inside the XML body. Found this pattern in the ALREADY-WORKING MailNotification
     *               integration (ESBRestClient.callSendEmail, same ESB host, different port) - it
     *               sets these exact 4 values as HTTP headers in addition to including them in its
     *               JSON body. Since the OCR SOAP endpoint has been rejecting every request so far
     *               with an undocumented gateway-level code (E000112) regardless of what we changed
     *               inside the body, it's worth testing whether the ESB Gateway itself expects these
     *               for routing/auth before a request ever reaches the OCR service.
     */
    private String post(String url, String requestXml, String accessToken, String rqUid, String funcId) throws IdrakException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(Config.getIdrakConnectionTimeout());
            connection.setReadTimeout(Config.getIdrakReadTimeout());
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/xml; charset=UTF-8");
            connection.setRequestProperty("RqUID", rqUid);
            connection.setRequestProperty("SCId", Config.getIdrakOcrScid());
            connection.setRequestProperty("ServiceId", Config.getIdrakOcrServiceId());
            connection.setRequestProperty("FuncId", funcId);
            // Per SOAP 1.1, the SOAPAction header value must be a quoted string - some stacks
            // (e.g. WCF) fail to route the request otherwise.
            connection.setRequestProperty("SOAPAction", "\"" + SOAP_ACTION + "\"");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);

            byte[] body = requestXml.getBytes("UTF-8");
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readResponse(statusCode == HttpURLConnection.HTTP_OK
                    ? connection.getInputStream() : connection.getErrorStream());

            if (statusCode != HttpURLConnection.HTTP_OK) {
                Log.error("IdrakOcrSoapClient: HTTP " + statusCode + " response=" + responseBody);
                throw new IdrakException("Idrak OCR SOAP call failed with HTTP status " + statusCode + ": " + responseBody);
            }
            return responseBody;
        } catch (IOException e) {
            throw new IdrakException("Error calling Idrak OCR SOAP endpoint " + url, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private OcrRequestResult parseResponse(String xml) {
        String statusCode = extractTag(xml, "StatusCode");
        String statusDesc = extractTag(xml, "StatusDesc");
        String requestId = extractTag(xml, "RequestId");
        String requestNum = extractTag(xml, "RequestNum");
        String requestStatusStr = extractTag(xml, "RequestStatus");
        Integer requestStatus = null;
        if (requestStatusStr != null) {
            try {
                requestStatus = Integer.parseInt(requestStatusStr.trim());
            } catch (NumberFormatException e) {
                Log.warn("IdrakOcrSoapClient: non-numeric RequestStatus '" + requestStatusStr + "'");
            }
        }
        return new OcrRequestResult(statusCode, statusDesc, requestId, requestNum, requestStatus);
    }

    /**
     * XSD's FileType_Type allowed values are short extensions ('pdf','jpeg','jpg','png'), NOT mime
     * types - confirmed by the client's own Appendix A samples ("FileType": "pdf"). Passing a raw
     * mime type like "application/pdf" here would be rejected, so map it first.
     */
    public static String mimeTypeToFileType(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String m = mimeType.toLowerCase();
        if (m.contains("pdf")) return "pdf";
        if (m.contains("jpeg") || m.contains("jpg")) return "jpeg";
        if (m.contains("png")) return "png";
        Log.warn("IdrakOcrSoapClient: unrecognized mime type '" + mimeType + "' - Idrak only accepts pdf/jpeg/jpg/png per its docs");
        return m;
    }

    private String buildRqUid() {
        // XSD: SCId||Date(DDMMYYYY)||(Sequence)#######
        String scid = Config.getIdrakOcrScid();
        String date = new SimpleDateFormat("ddMMyyyy").format(new Date());
        String seq = String.format("%07d", (System.currentTimeMillis() % 10000000));
        return scid + date + seq;
    }

    private String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
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

    private String extractTag(String xml, String tagName) {
        // Namespace-agnostic (handles <ns:Tag>, <Tag>, <tns:Tag>, etc.)
        Pattern pattern = Pattern.compile("<(?:\\w+:)?" + tagName + "[^>]*>([^<]*)</(?:\\w+:)?" + tagName + ">");
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
