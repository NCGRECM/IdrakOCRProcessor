package com.dataserve.ocr.mq;

/**
 * One FileInfo entry from an OCRFileStatusNotifyRq message (FuncId 53010201, delivered async via
 * the OCR.FileStatusMahfuthat queue) - JSON on the MQ queue, not SOAP/XML. OcrMqConsumer handles
 * both "ResponseDt" and "ResponseDate" field names, and ResponseFileNetId being a literal JSON null.
 */
public class OcrCompletionNotification {
    private String requestId;
    private String requestFileNetId;
    private String responseFileNetId;
    private Integer fileStatus;
    private String responseDate;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getRequestFileNetId() { return requestFileNetId; }
    public void setRequestFileNetId(String requestFileNetId) { this.requestFileNetId = requestFileNetId; }

    public String getResponseFileNetId() { return responseFileNetId; }
    public void setResponseFileNetId(String responseFileNetId) { this.responseFileNetId = responseFileNetId; }

    public Integer getFileStatus() { return fileStatus; }
    public void setFileStatus(Integer fileStatus) { this.fileStatus = fileStatus; }

    public String getResponseDate() { return responseDate; }
    public void setResponseDate(String responseDate) { this.responseDate = responseDate; }

    /** Per the client's Appendix (file status allowed values 1 / 1000-4002): 1 == success, anything else == failure/pending-error. */
    public boolean isSuccess() {
        return fileStatus != null && fileStatus == 1;
    }

    @Override
    public String toString() {
        return "{ requestId: " + requestId + ", requestFileNetId: " + requestFileNetId
                + ", responseFileNetId: " + responseFileNetId + ", fileStatus: " + fileStatus + " }";
    }
}
