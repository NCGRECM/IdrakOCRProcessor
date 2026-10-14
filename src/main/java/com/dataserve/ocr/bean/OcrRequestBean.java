package com.dataserve.ocr.bean;

import java.util.Date;

/**
 * Tracks one document's round-trip through the Idrak OCR integration:
 * our own document -> uploaded (encrypted) to client CMIS -> SOAP request sent ->
 * (async, via MQ) client notifies completion -> we fetch + decrypt the result ->
 * save it back as a new version of our own document.
 *
 * Persisted in the new OCR_REQUEST table (see db/migration_ocr_request.sql).
 */
public class OcrRequestBean {

	// statuses for OCR_REQUEST.STATUS
	public static final int STATUS_NEW = 0;                 // picked up from DMS_FILES, not yet uploaded
	public static final int STATUS_UPLOADED = 1;            // encrypted + uploaded to client CMIS (has requestFileNetId)
	public static final int STATUS_REQUEST_SENT = 2;        // IdrakOCRFileRequest SOAP call succeeded (has requestId/RequestNum)
	public static final int STATUS_COMPLETED_SUCCESS = 3;   // MQ notify received, FileStatus = success, result fetched/decrypted/saved
	public static final int STATUS_COMPLETED_FAILED = 4;    // MQ notify received, FileStatus = failure
	public static final int STATUS_FAILED = 5;              // any local failure (upload/soap/decrypt/save)

	private Long id;                       // OCR_REQUEST.ID (identity)
	private String documentId;             // our DMS_FILES.DOCUMENT_ID (FileNet id in our own Content Engine)
	private String documentClass;
	private Long fileId;
	private String fileName;

	private String requestId;              // our own generated correlation id (GUID), sent as RqUID / RequestId to Idrak
	private String requestFileNetId;        // FileNet object id of the ENCRYPTED file we uploaded into the client's OCRIdark folder
	private String responseFileNetId;      // FileNet object id of the encrypted OCR result, from the async MQ notify
	private Integer fileStatus;            // Idrak's own FileStatus code (see Appendix - 1/1000-4002 etc.)

	private int status = STATUS_NEW;
	private String errorMessage;
	private String newDocumentId;          // our new FileNet document id after saving the decrypted OCR result as a new version

	private Date createdDate;
	private Date updatedDate;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }

	public String getDocumentId() { return documentId; }
	public void setDocumentId(String documentId) { this.documentId = documentId; }

	public String getDocumentClass() { return documentClass; }
	public void setDocumentClass(String documentClass) { this.documentClass = documentClass; }

	public Long getFileId() { return fileId; }
	public void setFileId(Long fileId) { this.fileId = fileId; }

	public String getFileName() { return fileName; }
	public void setFileName(String fileName) { this.fileName = fileName; }

	public String getRequestId() { return requestId; }
	public void setRequestId(String requestId) { this.requestId = requestId; }

	public String getRequestFileNetId() { return requestFileNetId; }
	public void setRequestFileNetId(String requestFileNetId) { this.requestFileNetId = requestFileNetId; }

	public String getResponseFileNetId() { return responseFileNetId; }
	public void setResponseFileNetId(String responseFileNetId) { this.responseFileNetId = responseFileNetId; }

	public Integer getFileStatus() { return fileStatus; }
	public void setFileStatus(Integer fileStatus) { this.fileStatus = fileStatus; }

	public int getStatus() { return status; }
	public void setStatus(int status) { this.status = status; }

	public String getErrorMessage() { return errorMessage; }
	public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

	public String getNewDocumentId() { return newDocumentId; }
	public void setNewDocumentId(String newDocumentId) { this.newDocumentId = newDocumentId; }

	public Date getCreatedDate() { return createdDate; }
	public void setCreatedDate(Date createdDate) { this.createdDate = createdDate; }

	public Date getUpdatedDate() { return updatedDate; }
	public void setUpdatedDate(Date updatedDate) { this.updatedDate = updatedDate; }

	@Override
	public String toString() {
		return "{ documentId: " + documentId + ", requestId: " + requestId
				+ ", requestFileNetId: " + requestFileNetId + ", responseFileNetId: " + responseFileNetId
				+ ", status: " + status + " }";
	}
}
