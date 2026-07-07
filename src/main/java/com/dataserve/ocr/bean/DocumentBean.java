package com.dataserve.ocr.bean;

public class DocumentBean {
	private String documentId;
	private String documentClass;
	private String newDocumentId;
	private Long fileId;
	private String fileName;
	private int status;
	private String errorMassage;
	private String currentVersionDocumentId;
	private boolean isCurrentVersion = true;
	private boolean hasOCRVersionBefore;
	
	public static final int STATUS_NEW = 0;
	public static final int STATUS_RUN = 1;
	public static final int STATUS_FETCH = 2;
	public static final int STATUS_OCR = 3;
	public static final int STATUS_IGNORE = 4;
	public static final int STATUS_SUCCESS = 5;
	public static final int STATUS_FAIL = 6;
	
	
	public String getDocumentId() {
		return documentId;
	}
	public void setDocumentId(String documentId) {
		this.documentId = documentId;
	}
	public String getFileName() {
		return fileName;
	}
	public void setFileName(String fileName) {
		this.fileName = fileName;
	}
	public int getStatus() {
		return status;
	}
	public void setStatus(int status) {
		this.status = status;
	}
	
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("{ documentId: " + documentId);
		sb.append(", fileName: " + fileName);
		sb.append(", status: " + status + " }");
		return sb.toString();
	}
	public String getDocumentClass() {
		return documentClass;
	}
	public void setDocumentClass(String documentClass) {
		this.documentClass = documentClass;
	}
	public String getErrorMassage() {
		return errorMassage;
	}
	public void setErrorMassage(String errorMassage) {
		this.errorMassage = errorMassage;
	}
	public Long getFileId() {
		return fileId;
	}
	public void setFileId(Long fileId) {
		this.fileId = fileId;
	}
	public String getNewDocumentId() {
		return newDocumentId;
	}
	public void setNewDocumentId(String newDocumentId) {
		this.newDocumentId = newDocumentId;
	}
	public String getCurrentVersionDocumentId() {
		return currentVersionDocumentId;
	}
	public void setCurrentVersionDocumentId(String currentVersionDocumentId) {
		this.currentVersionDocumentId = currentVersionDocumentId;
	}
	public boolean isCurrentVersion() {
		return isCurrentVersion;
	}
	public void setCurrentVersion(boolean isCurrentVersion) {
		this.isCurrentVersion = isCurrentVersion;
	}
	public boolean isHasOCRVersionBefore() {
		return hasOCRVersionBefore;
	}
	public void setHasOCRVersionBefore(boolean hasOCRVersionBefore) {
		this.hasOCRVersionBefore = hasOCRVersionBefore;
	}
	
	
}
