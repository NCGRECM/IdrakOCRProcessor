package com.dataserve.ocr.tasks;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import javax.security.auth.Subject;

import com.dataserve.ocr.bean.DocumentBean;
import com.dataserve.ocr.bean.OcrRequestBean;
import com.dataserve.ocr.cmis.IdrakCmisClient;
import com.dataserve.ocr.crypto.CryptoUtil;
import com.dataserve.ocr.database.DatabaseManager;
import com.dataserve.ocr.exception.CMException;
import com.dataserve.ocr.exception.OCRException;
import com.dataserve.ocr.idrak.IdrakOcrSoapClient;
import com.dataserve.ocr.idrak.OcrRequestResult;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;
import com.filenet.api.collection.ContentElementList;
import com.filenet.api.constants.PropertyNames;
import com.filenet.api.core.Connection;
import com.filenet.api.core.ContentTransfer;
import com.filenet.api.core.Document;
import com.filenet.api.core.Domain;
import com.filenet.api.core.Factory;
import com.filenet.api.core.ObjectStore;
import com.filenet.api.property.FilterElement;
import com.filenet.api.property.PropertyFilter;
import com.filenet.api.util.UserContext;

/**
 * Replaces the old local-Tesseract OCRTask. For each pending document:
 *  1. fetch its content from OUR OWN FileNet Content Engine (unchanged from the old flow),
 *  2. encrypt it with the client's (Idrak) public key,
 *  3. upload the encrypted file into the client's CMIS-hosted OCRIdark folder,
 *  4. call the IdrakOCRFileRequest SOAP service to submit it for OCR,
 *  5. record everything in OCR_REQUEST so the async MQ notification (see OcrMqConsumer / the
 *     completion phase in StarterTask) can be matched back to this document by RequestId.
 *
 * This task does NOT get the OCR result back - that only happens later, out of band, via MQ.
 */
public class OcrUploadTask implements Callable<DocumentBean> {

    private DocumentBean bean;
    private String accessToken;
    private Connection conn;
    private ObjectStore os;

    public OcrUploadTask(DocumentBean bean, String accessToken) {
        this.bean = bean;
        this.accessToken = accessToken;
    }

    @Override
    public DocumentBean call() throws Exception {
        Log.debug(Thread.currentThread().getName() + ": starting Idrak OCR upload for document " + bean.getDocumentId());
        File sourceFile = null;
        DatabaseManager dm = null;
        String requestId = IdrakOcrSoapClient.newRequestId();
        try {
            bean.setStatus(DocumentBean.STATUS_RUN);
            openCMConnection();
            Document doc = getDocument();

            // need mimeType/fileType before writing the attachment to disk, so the temp file gets a
            // real extension (Idrak's upload validation can key off it)
            String mimeType = doc.getProperties().get(PropertyNames.MIME_TYPE).getStringValue();
            String fileType = IdrakOcrSoapClient.mimeTypeToFileType(mimeType);

            // must check before getDocumentAttachment(): an unrecognized mimeType (e.g. "text/plain")
            // is returned as-is and its "/" breaks the temp filename on Windows
            if (!"pdf".equals(fileType) && !"jpeg".equals(fileType) && !"png".equals(fileType)) {
                throw new OCRException("File " + bean.getDocumentId() + " has unsupported type '" + mimeType
                        + "' - Idrak only accepts pdf/jpeg/jpg/png (error 3001/3009)");
            }

            List<File> attachments = getDocumentAttachment(doc, fileType);
            if (attachments.isEmpty()) {
                bean.setStatus(DocumentBean.STATUS_IGNORE);
                Log.debug(Thread.currentThread().getName() + ": document " + bean.getDocumentId() + " has no attachment content, ignored");
                return bean;
            }
            sourceFile = attachments.get(0);
            bean.setFileName(sourceFile.getName());

            // Idrak's own limit is 50MB (error 3010) - fail fast rather than waste an upload on it
            long sizeBytes = sourceFile.length();
            long maxBytes = 50L * 1024 * 1024;
            if (sizeBytes > maxBytes) {
                throw new OCRException("File " + bean.getDocumentId() + " is " + sizeBytes
                        + " bytes, exceeds Idrak's 50MB limit (error 3010) - not submitting");
            }

            dm = new DatabaseManager();
            OcrRequestBean requestBean = new OcrRequestBean();
            requestBean.setDocumentId(bean.getDocumentId());
            requestBean.setDocumentClass(bean.getDocumentClass());
            requestBean.setFileId(bean.getFileId());
            requestBean.setFileName(bean.getFileName());
            requestBean.setRequestId(requestId);
            dm.insertOcrRequest(requestBean);

            Log.debug(Thread.currentThread().getName() + ": encrypting " + sourceFile.getName() + " for Idrak");
            byte[] rawContent = Files.readAllBytes(sourceFile.toPath());
            byte[] encryptedContent = CryptoUtil.encryptForIdrak(rawContent);

            Log.debug(Thread.currentThread().getName() + ": uploading encrypted file to client CMIS (OCRIdark)");
            IdrakCmisClient cmisClient = new IdrakCmisClient();
            String clientFileNetId = cmisClient.uploadNewDocument(encryptedContent, sourceFile.getName(), mimeType);
            dm.markUploaded(requestId, clientFileNetId);

            Log.debug(Thread.currentThread().getName() + ": submitting IdrakOCRFileRequest, requestId=" + requestId);
            IdrakOcrSoapClient soapClient = new IdrakOcrSoapClient();
            OcrRequestResult result = soapClient.submitOcrRequest(accessToken, requestId, clientFileNetId, fileType);
            if (!result.isSuccess()) {
                throw new CMException("IdrakOCRFileRequest returned non-success status: " + result);
            }
            dm.markRequestSent(requestId);

            // in-flight at Idrak, not OCR'd yet - keep exculded_fetch_document_status_for_OCR
            // including 7 in config so it isn't re-picked-up meanwhile
            bean.setStatus(7);
            Log.debug(Thread.currentThread().getName() + ": document " + bean.getDocumentId()
                    + " submitted to Idrak OK, requestId=" + requestId + ", clientFileNetId=" + clientFileNetId);

        } catch (Exception e) {
            Log.error("Error submitting document " + bean.getDocumentId() + " to Idrak OCR", e);
            bean.setStatus(DocumentBean.STATUS_FAIL);
            bean.setErrorMassage(e.getMessage());
            try {
                if (dm == null) {
                    dm = new DatabaseManager();
                }
                dm.markFailed(requestId, e.getMessage());
            } catch (Exception ex) {
                Log.error("Error recording OCR_REQUEST failure for requestId " + requestId, ex);
            }
        } finally {
            closeCMConnection();
            if (sourceFile != null) {
                try {
                    Files.deleteIfExists(sourceFile.toPath());
                } catch (Exception e) {
                    Log.warn("Source file " + sourceFile.getAbsolutePath() + " couldn't be deleted", e);
                }
            }
            updateStatus();
            if (dm != null) {
                dm.close();
            }
        }
        return bean;
    }

    private void openCMConnection() {
        conn = Factory.Connection.getConnection(Config.getContentEngineURI());
        Subject subject = UserContext.createSubject(conn, Config.getContentEngineUsername(), Config.getContentEnginePassword(), null);
        UserContext.get().pushSubject(subject);
        Domain domain = Factory.Domain.fetchInstance(conn, null, null);
        os = Factory.ObjectStore.fetchInstance(domain, Config.getObjectStoreName(), null);
    }

    private Document getDocument() throws CMException {
        PropertyFilter pf = new PropertyFilter();
        pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.RETRIEVAL_NAME, null));
        pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.CONTENT_SIZE, null));
        pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.CONTENT_ELEMENTS, null));
        pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.IS_CURRENT_VERSION, null));
        pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.MIME_TYPE, null));

        try {
            return Factory.Document.fetchInstance(os, "{" + bean.getDocumentId() + "}", pf);
        } catch (Exception e) {
            throw new CMException("Error fetching document", e);
        }
    }

    /**
     * @param fileType "pdf"/"jpeg"/"png" (from IdrakOcrSoapClient.mimeTypeToFileType) - or null if
     *                 unrecognized - used only to give the temp file a real extension; may be null,
     *                 in which case the file is written with no extension as before.
     */
    private List<File> getDocumentAttachment(Document doc, String fileType) throws CMException {
        List<File> attachments = new java.util.ArrayList<>();
        try {
            ContentElementList docContentList = doc.get_ContentElements();
            Iterator iter = docContentList.iterator();
            while (iter.hasNext()) {
                ContentTransfer ct = (ContentTransfer) iter.next();
                // real extension matching content type, instead of none at all
                String extension = (fileType != null) ? ("." + fileType) : "";
                File file = new File(bean.getDocumentId() + extension);
                try (InputStream is = ct.accessContentStream(); OutputStream os = new FileOutputStream(file)) {
                    // fixed-size buffer - is.available() can return 0 on a network-backed stream and
                    // hang this loop forever
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                    attachments.add(file);
                } catch (IOException ioe) {
                    throw new CMException("Error getting attachment content", ioe);
                }
                break;
            }
        } catch (Exception e) {
            throw new CMException("Error getting attachment content", e);
        }
        return attachments;
    }

    private void closeCMConnection() throws Exception {
        UserContext.get().popSubject();
    }

    private void updateStatus() {
        Log.debug("Updating DMS_FILES for document " + bean.getDocumentId() + " status=" + bean.getStatus());
        DatabaseManager dm = null;
        try {
            dm = new DatabaseManager();
            dm.updateTable(bean);
            dm.logAudit(bean);
        } catch (Exception e) {
            Log.error("Error updating DMS_FILES for document " + bean.getDocumentId(), e);
        } finally {
            if (dm != null) {
                dm.close();
            }
        }
    }
}
