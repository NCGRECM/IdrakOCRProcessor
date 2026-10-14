package com.dataserve.ocr.tasks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;

import javax.security.auth.Subject;

import com.dataserve.ocr.bean.DocumentBean;
import com.dataserve.ocr.bean.OcrRequestBean;
import com.dataserve.ocr.cmis.IdrakCmisClient;
import com.dataserve.ocr.crypto.CryptoUtil;
import com.dataserve.ocr.database.DatabaseManager;
import com.dataserve.ocr.exception.CMException;
import com.dataserve.ocr.mq.OcrCompletionNotification;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;
import com.filenet.api.collection.ContentElementList;
import com.filenet.api.constants.AutoClassify;
import com.filenet.api.constants.CheckinType;
import com.filenet.api.constants.PropertyNames;
import com.filenet.api.constants.RefreshMode;
import com.filenet.api.constants.ReservationType;
import com.filenet.api.core.ContentTransfer;
import com.filenet.api.core.Document;
import com.filenet.api.core.Domain;
import com.filenet.api.core.Factory;
import com.filenet.api.core.ObjectStore;
import com.filenet.api.property.FilterElement;
import com.filenet.api.property.PropertyFilter;
import com.filenet.api.util.UserContext;

/**
 * Handles one OCRFileStatusNotifyRq entry pulled off the MQ queue: fetches the (still encrypted)
 * OCR result from the client's CMIS repo by ResponseFileNetId, decrypts it with our private key,
 * and saves it as a new major version of our own document back in OUR Content Engine - the same
 * checkout/checkin pattern the old local-Tesseract flow used to save its output.
 */
public class OcrCompletionTask {

    public void process(OcrCompletionNotification notification, DatabaseManager dm) throws Exception {
        String requestId = notification.getRequestId();
        OcrRequestBean requestBean = dm.findByRequestId(requestId);
        if (requestBean == null) {
            // Could be a notification for a request this batch instance didn't originate (e.g. sent by
            // another environment/instance), or a requestId we've already processed and since lost track
            // of. Log and skip rather than fail the whole drain.
            Log.warn("OcrCompletionTask: no OCR_REQUEST found for requestId " + requestId + " - skipping notification " + notification);
            Log.mqWarn("No OCR_REQUEST found for requestId " + requestId + " - skipping notification " + notification);
            return;
        }

        if (!notification.isSuccess()) {
            applyFailure(requestBean, notification.getFileStatus(), dm, "MQ notify");
            return;
        }

        applySuccess(requestBean, notification.getResponseFileNetId(), notification.getFileStatus(), dm, "MQ notify");
    }

    /**
     * Shared by both completion paths: the async MQ notify (process(), above) and the
     * checkRequestStatus polling fallback (OcrStatusPollingTask) - fetches the (still encrypted) OCR
     * result from the client's CMIS repo, decrypts it, and saves it as a new version of our own
     * document. Caller is responsible for having already confirmed success (FileStatus==1 for the MQ
     * path, requestStatus==SUCCESS for the polling path) before calling this.
     *
     * @param source short label for log lines only ("MQ notify" / "status poll"), so it's obvious
     *               from the logs which path completed a given request.
     */
    public void applySuccess(OcrRequestBean requestBean, String responseFileNetId, Integer fileStatus, DatabaseManager dm, String source)
            throws Exception {
        String requestId = requestBean.getRequestId();
        Log.debug("OcrCompletionTask (" + source + "): fetching OCR result for requestId=" + requestId
                + " responseFileNetId=" + responseFileNetId);
        IdrakCmisClient cmisClient = new IdrakCmisClient();
        byte[] encryptedResult = cmisClient.fetchResultContent(responseFileNetId);
        byte[] decryptedResult = CryptoUtil.decryptOurResult(encryptedResult);

        String newDocumentId = saveAsNewVersion(requestBean.getDocumentId(), decryptedResult, requestBean.getFileName());

        dm.markCompleted(requestId, responseFileNetId, fileStatus == null ? 1 : fileStatus, true, newDocumentId);
        dm.finalizeDmsFilesDocument(requestBean.getDocumentId(), newDocumentId, true);
        logAudit(requestBean, DocumentBean.STATUS_SUCCESS, newDocumentId, dm);
        Log.debug("OcrCompletionTask (" + source + "): requestId=" + requestId + " completed, newDocumentId=" + newDocumentId);
        Log.mqInfo("Completed requestId=" + requestId + " documentId=" + requestBean.getDocumentId()
                + " -> newDocumentId=" + newDocumentId + " (via " + source + ")");
    }

    /** Terminal failure for a request - no result to fetch, just mark it done (failed) on our side so nothing keeps re-polling it. */
    public void applyFailure(OcrRequestBean requestBean, Integer fileStatus, DatabaseManager dm, String source) throws Exception {
        String requestId = requestBean.getRequestId();
        Log.warn("OcrCompletionTask (" + source + "): Idrak reported failure for requestId " + requestId
                + " (FileStatus=" + fileStatus + ") - see client's Appendix for the meaning of this code");
        Log.mqWarn("Idrak reported failure for requestId " + requestId + " documentId=" + requestBean.getDocumentId()
                + " (FileStatus=" + fileStatus + ", via " + source + ")");
        dm.markCompleted(requestId, null, fileStatus == null ? -1 : fileStatus, false, null);
        dm.finalizeDmsFilesDocument(requestBean.getDocumentId(), null, false);
        logAudit(requestBean, DocumentBean.STATUS_FAIL, null, dm);
    }

    // OCR_REQUEST uses its own STATUS scale; OCR_AUDIT/DMS_FILES uses DocumentBean.STATUS_*, so
    // build one of those to log a completion row here (previously only logged on upload).
    private void logAudit(OcrRequestBean requestBean, int status, String newDocumentId, DatabaseManager dm) {
        DocumentBean auditBean = new DocumentBean();
        auditBean.setDocumentId(requestBean.getDocumentId());
        auditBean.setDocumentClass(requestBean.getDocumentClass());
        auditBean.setFileId(requestBean.getFileId());
        auditBean.setFileName(requestBean.getFileName());
        auditBean.setStatus(status);
        auditBean.setErrorMassage(requestBean.getErrorMessage());
        auditBean.setNewDocumentId(newDocumentId);
        try {
            dm.logAudit(auditBean);
        } catch (Exception e) {
            Log.error("Error logging OCR_AUDIT for document " + requestBean.getDocumentId(), e);
        }
    }

    /**
     * Checks out our own document and checks it back in as a major version with two content
     * elements, same as the old Tesseract TXT flow: ESN 0 keeps the original attachment as-is (so
     * Navigator still shows/opens the original scanned file), ESN 1 is the new OCR text result.
     */
    private String saveAsNewVersion(String documentId, byte[] ocrResult, String fileName) throws CMException {
        com.filenet.api.core.Connection conn = null;
        try {
            conn = Factory.Connection.getConnection(Config.getContentEngineURI());
            Subject subject = UserContext.createSubject(conn, Config.getContentEngineUsername(), Config.getContentEnginePassword(), null);
            UserContext.get().pushSubject(subject);
            Domain domain = Factory.Domain.fetchInstance(conn, null, null);
            ObjectStore os = Factory.ObjectStore.fetchInstance(domain, Config.getObjectStoreName(), null);

            PropertyFilter pf = new PropertyFilter();
            pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.CONTENT_ELEMENTS, null));
            pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.MIME_TYPE, null));
            // Must be in the filter, or get_IsReserved() below throws API_PROPERTY_NOT_IN_CACHE.
            pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.IS_RESERVED, null));
            Document doc = Factory.Document.fetchInstance(os, "{" + documentId + "}", pf);

            // grab the original content before checkout replaces it
            byte[] originalContent = null;
            String originalMimeType = doc.get_MimeType();
            String originalRetrievalName = fileName;
            Iterator iter = doc.get_ContentElements().iterator();
            if (iter.hasNext()) {
                ContentTransfer existing = (ContentTransfer) iter.next();
                originalRetrievalName = existing.get_RetrievalName();
                try (InputStream is = existing.accessContentStream()) {
                    originalContent = readAllBytes(is);
                }
            }

            if (!doc.get_IsReserved()) {
                doc.checkout(ReservationType.OBJECT_STORE_DEFAULT, null, null, null);
                doc.save(RefreshMode.REFRESH);
            }
            Document reservation = (Document) doc.get_Reservation();

            ContentElementList cel = Factory.ContentElement.createList();

            if (originalContent != null) {
                ContentTransfer cto = Factory.ContentTransfer.createInstance();
                cto.set_ContentType(originalMimeType);
                cto.setCaptureSource(new ByteArrayInputStream(originalContent));
                cto.set_RetrievalName(originalRetrievalName);
                cel.add(cto);
            }

            // Idrak's getContentStream always comes back as plain extracted text - same as the old
            // Tesseract TXT output mode, so it goes in the same way (text/plain, name + ".txt").
            ContentTransfer ct = Factory.ContentTransfer.createInstance();
            ct.set_ContentType("text/plain");
            ct.setCaptureSource(new ByteArrayInputStream(ocrResult));
            ct.set_RetrievalName((originalRetrievalName != null ? originalRetrievalName : "ocr-result") + ".txt");
            cel.add(ct);

            reservation.get_ContentElements().clear();
            reservation.get_ContentElements().addAll(cel);

            reservation.checkin(AutoClassify.DO_NOT_AUTO_CLASSIFY, CheckinType.MAJOR_VERSION);
            reservation.save(RefreshMode.REFRESH);
            return reservation.get_Id().toString().replace("{", "").replace("}", "");
        } catch (Exception e) {
            throw new CMException("Error saving OCR result as new version of document " + documentId, e);
        } finally {
            try {
                UserContext.get().popSubject();
            } catch (Exception e) {
                Log.warn("Error popping FileNet subject", e);
            }
        }
    }

    private byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = is.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
