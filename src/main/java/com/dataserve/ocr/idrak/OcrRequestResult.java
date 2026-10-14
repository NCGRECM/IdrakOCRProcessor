package com.dataserve.ocr.idrak;

/** Parsed response of a single IdrakOCRFileRequestRq call (FuncId 53010101). Sync ack only - not the OCR result itself. */
public class OcrRequestResult {

    /**
     * requestStatus enum:
     *   1 = Pending            (في الانتظار)
     *   2 = Success             (مكتمل)           <- pull the result and mark our side completed
     *   3 = Failure             (فشل في الارسال)   <- terminal failure, stop polling this request
     *   4 = InProgress          (قيد المعالجة)
     *   5 = ProcessedFailed     (فشل المعالجة)     <- terminal failure, stop polling this request
     *   6 = Canceled            (تم الالغاء)       <- terminal, stop polling this request
     * 1 and 4 are the only non-terminal states - a polling loop should leave those alone and check
     * again next run.
     */
    public static final int REQUEST_STATUS_PENDING = 1;
    public static final int REQUEST_STATUS_SUCCESS = 2;
    public static final int REQUEST_STATUS_FAILURE = 3;
    public static final int REQUEST_STATUS_IN_PROGRESS = 4;
    public static final int REQUEST_STATUS_PROCESSED_FAILED = 5;
    public static final int REQUEST_STATUS_CANCELED = 6;

    private final String statusCode;
    private final String statusDesc;
    private final String requestId;
    private final String requestNum;
    private final Integer requestStatus;
    private final String responseFileNetId;
    private final Integer fileStatus;

    public OcrRequestResult(String statusCode, String statusDesc, String requestId, String requestNum, Integer requestStatus) {
        this(statusCode, statusDesc, requestId, requestNum, requestStatus, null, null);
    }

    public OcrRequestResult(String statusCode, String statusDesc, String requestId, String requestNum, Integer requestStatus,
                            String responseFileNetId, Integer fileStatus) {
        this.statusCode = statusCode;
        this.statusDesc = statusDesc;
        this.requestId = requestId;
        this.requestNum = requestNum;
        this.requestStatus = requestStatus;
        this.responseFileNetId = responseFileNetId;
        this.fileStatus = fileStatus;
    }

    /**
     * Whether the SOAP CALL ITSELF was accepted (success StatusCode is "I000000"). Does NOT mean
     * the OCR itself succeeded - the real outcome comes later via MQ or polling (see getRequestStatus()).
     */
    public boolean isSuccess() {
        return "I000000".equals(statusCode);
    }

    public String getStatusCode() { return statusCode; }
    public String getStatusDesc() { return statusDesc; }
    public String getRequestId() { return requestId; }
    public String getRequestNum() { return requestNum; }

    /** Only populated for checkRequestStatus (FuncId 53010102) responses - see the REQUEST_STATUS_* constants above. Null for submitOcrRequest. */
    public Integer getRequestStatus() { return requestStatus; }

    /**
     * CMIS/FileNet object id of the OCR result in the client's repo, when requestStatus == SUCCESS.
     * TODO: tag name assumed to be "ResponseFileNetId" (same as the async MQ notify) - not yet
     * confirmed against a real checkRequestStatus Success response.
     */
    public String getResponseFileNetId() { return responseFileNetId; }

    /** Per-file status from the FileList/FileInfo block, when present (same code space as the MQ notify's FileStatus: 1 = success). */
    public Integer getFileStatus() { return fileStatus; }

    @Override
    public String toString() {
        return "{ statusCode: " + statusCode + ", statusDesc: " + statusDesc
                + ", requestId: " + requestId + ", requestNum: " + requestNum
                + ", requestStatus: " + requestStatus + ", responseFileNetId: " + responseFileNetId
                + ", fileStatus: " + fileStatus + " }";
    }
}
