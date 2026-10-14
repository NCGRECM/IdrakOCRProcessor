package com.dataserve.ocr.tasks;

import java.util.List;

import com.dataserve.ocr.bean.OcrRequestBean;
import com.dataserve.ocr.database.DatabaseManager;
import com.dataserve.ocr.idrak.IdrakOcrSoapClient;
import com.dataserve.ocr.idrak.OcrRequestResult;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;

/**
 * Fallback completion path, run AFTER the MQ completion phase (see StarterTask): for any
 * OCR_REQUEST still sitting in STATUS_REQUEST_SENT and old enough (Config.getOcrStatusPollingMinAgeMinutes()),
 * calls Check Request Status (FuncId 53010102) directly instead of waiting on the async MQ notify.
 *
 * Per the client's own guidance, checkRequestStatus "should be used with files with no response for
 * long time only" - this is why it only looks at requests past the min-age threshold, not every
 * REQUEST_SENT row on every run.
 *
 * requestStatus handling (see OcrRequestResult.REQUEST_STATUS_*):
 *   1 Pending / 4 InProgress  -> leave alone, check again next run
 *   2 Success                 -> pull the result (same fetch+decrypt+save path as the MQ completion task)
 *                                 and update our side immediately
 *   3 Failure / 5 ProcessedFailed / 6 Canceled -> terminal failure, mark completed-failed so we stop polling it
 */
public class OcrStatusPollingTask {

    public void run(String accessToken, DatabaseManager dm) {
        List<OcrRequestBean> candidates;
        try {
            candidates = dm.findRequestsAwaitingStatusPoll(Config.getOcrStatusPollingMinAgeMinutes());
        } catch (Exception e) {
            Log.error("OcrStatusPollingTask: error fetching candidates - skipping this run", e);
            return;
        }

        if (candidates.isEmpty()) {
            Log.debug("OcrStatusPollingTask: no REQUEST_SENT rows old enough to poll");
            return;
        }

        Log.debug("OcrStatusPollingTask: polling status for " + candidates.size() + " request(s)");
        IdrakOcrSoapClient soapClient = new IdrakOcrSoapClient();
        OcrCompletionTask completionTask = new OcrCompletionTask();

        for (OcrRequestBean requestBean : candidates) {
            String requestId = requestBean.getRequestId();
            try {
                OcrRequestResult result = soapClient.checkRequestStatus(accessToken, requestId);
                if (!result.isSuccess()) {
                    // SOAP call itself was rejected (gateway/StatusCode level, e.g. E000112) - not a
                    // verdict on the OCR request, just leave it for next run rather than guessing.
                    Log.warn("OcrStatusPollingTask: checkRequestStatus call failed for requestId=" + requestId
                            + " statusCode=" + result.getStatusCode() + " statusDesc=" + result.getStatusDesc());
                    continue;
                }

                Integer requestStatus = result.getRequestStatus();
                if (requestStatus == null) {
                    Log.warn("OcrStatusPollingTask: no RequestStatus in response for requestId=" + requestId + " - " + result);
                    continue;
                }

                if (requestStatus == OcrRequestResult.REQUEST_STATUS_SUCCESS) {
                    String responseFileNetId = result.getResponseFileNetId();
                    if (responseFileNetId == null) {
                        // SUCCESS with no ResponseFileNetId isn't worth retrying forever - treat it as
                        // a terminal failure so the row stops being re-polled on every run.
                        Log.error("OcrStatusPollingTask: requestStatus=SUCCESS for requestId=" + requestId
                                + " but no ResponseFileNetId could be parsed from the response - " + result
                                + " - treating as a failure so this request stops being re-polled");
                        completionTask.applyFailure(requestBean, result.getFileStatus(), dm, "status poll");
                        continue;
                    }
                    Log.debug("OcrStatusPollingTask: requestId=" + requestId + " is Success - pulling result now");
                    completionTask.applySuccess(requestBean, responseFileNetId, result.getFileStatus(), dm, "status poll");

                } else if (requestStatus == OcrRequestResult.REQUEST_STATUS_FAILURE
                        || requestStatus == OcrRequestResult.REQUEST_STATUS_PROCESSED_FAILED
                        || requestStatus == OcrRequestResult.REQUEST_STATUS_CANCELED) {
                    Log.debug("OcrStatusPollingTask: requestId=" + requestId + " is terminal failure (requestStatus=" + requestStatus + ")");
                    completionTask.applyFailure(requestBean, result.getFileStatus(), dm, "status poll");

                } else {
                    // 1 Pending / 4 InProgress - still working, nothing to do yet.
                    Log.debug("OcrStatusPollingTask: requestId=" + requestId + " still " + requestStatus + " (Pending/InProgress) - will check again next run");
                }

            } catch (Exception e) {
                Log.error("OcrStatusPollingTask: error polling/applying status for requestId=" + requestId, e);
            }
        }
    }
}
