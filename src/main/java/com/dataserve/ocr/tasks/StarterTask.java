package com.dataserve.ocr.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import com.dataserve.ocr.bean.DocumentBean;
import com.dataserve.ocr.database.DatabaseManager;
import com.dataserve.ocr.exception.DatabaseException;
import com.dataserve.ocr.idrak.IdrakAuthClient;
import com.dataserve.ocr.idrak.IdrakTokenResponse;
import com.dataserve.ocr.mq.OcrCompletionNotification;
import com.dataserve.ocr.mq.OcrMqConsumer;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;

/**
 * Each run has two independent phases:
 *
 *  Phase A - upload: pick up documents still awaiting OCR (DMS_FILES.OCR_STATUS not in the excluded
 *  list), encrypt + upload each to the client's CMIS repo, and submit an IdrakOCRFileRequest for it.
 *  This does NOT get the OCR result back - only an ack.
 *
 *  Phase B - completion: drain the OCR.FileStatusMahfuthat MQ queue for whatever completion
 *  notifications have arrived since last run, fetch + decrypt each finished result, and save it back
 *  as a new version of the original document.
 *
 *  Phase C - status polling fallback: for requests that have been sitting in STATUS_REQUEST_SENT for
 *  a while with no MQ notify (Config.getOcrStatusPollingMinAgeMinutes()), actively ask Idrak via
 *  Check Request Status (FuncId 53010102) instead of waiting indefinitely. Same completion logic as
 *  Phase B (OcrCompletionTask.applySuccess/applyFailure) - see OcrStatusPollingTask.
 *
 * The phases are independent on purpose: a document uploaded in run N might not get its MQ
 * notification until run N+3, and that's fine - OCR_REQUEST tracks it by RequestId in the meantime.
 */
public class StarterTask extends TimerTask {
    private static final BlockingQueue<Callable<DocumentBean>> queue = new ArrayBlockingQueue<Callable<DocumentBean>>(Config.getBatchSize());

    @Override
    public void run() {
        Log.debug("OCR Processor Started");
        String accessToken = null;
        try {
            Log.debug("Logging in to Idrak OCR API ...");
            IdrakTokenResponse tokenResponse = new IdrakAuthClient().login();
            accessToken = tokenResponse.getAuthorizationHeader().replaceFirst("(?i)^bearer\\s+", "");
            Log.debug("Idrak OCR API login succeeded. Token expires in " + tokenResponse.getExpiresIn() + " seconds");
        } catch (Exception e) {
            Log.error("Error logging in to Idrak OCR API - skipping this run entirely", e);
            return;
        }

        runUploadPhase(accessToken);
        runCompletionPhase();
        runStatusPollingPhase(accessToken);

        Log.debug("OCR Processor run completed");
    }

    private void runUploadPhase(String accessToken) {
        DatabaseManager dm = null;
        ThreadPoolExecutor executor = null;
        try {
            dm = new DatabaseManager();
            executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Config.getNumberOfProcessingThreads());

            while (dm.haveMoreDocuments()) {
                queue.clear();
                Log.debug("Fetching documents awaiting OCR upload ...");
                List<DocumentBean> docIds = dm.fetchDocuments();

                Log.debug("Queueing Idrak upload tasks for " + docIds.size() + " document(s) ...");
                for (DocumentBean docId : docIds) {
                    queue.add(new OcrUploadTask(docId, accessToken));
                }

                List<Future<DocumentBean>> futures = new ArrayList<>();
                for (Callable<DocumentBean> call : queue) {
                    futures.add(executor.submit(call));
                }
                for (Future<DocumentBean> future : futures) {
                    try {
                        future.get();
                    } catch (Exception e) {
                        Log.error("Error waiting for Idrak upload task", e);
                    }
                }
            }
            Log.debug("No more documents to upload for this run");
        } catch (DatabaseException e) {
            Log.error("Error accessing database during upload phase", e);
        } catch (Exception e) {
            Log.error("Error running upload phase", e);
        } finally {
            if (dm != null) {
                dm.close();
            }
            if (executor != null) {
                executor.shutdown();
            }
        }
    }

    private void runCompletionPhase() {
        DatabaseManager dm = null;
        try {
            dm = new DatabaseManager();

            // Skip the MQ connection entirely when nothing is in-flight in OCR_REQUEST.
            if (!dm.hasInFlightOcrRequests()) {
                Log.debug("No in-flight OCR_REQUEST rows - skipping MQ connection for this run");
                return;
            }

            Log.debug("Draining OCR.FileStatusMahfuthat queue for completion notifications ...");
            List<OcrCompletionNotification> notifications;
            try {
                notifications = new OcrMqConsumer().drainQueue();
            } catch (Exception e) {
                Log.error("Error draining MQ completion queue - will retry next run", e);
                return;
            }

            if (notifications.isEmpty()) {
                Log.debug("No completion notifications waiting");
                return;
            }

            OcrCompletionTask completionTask = new OcrCompletionTask();
            for (OcrCompletionNotification notification : notifications) {
                try {
                    completionTask.process(notification, dm);
                } catch (Exception e) {
                    Log.error("Error processing completion notification " + notification, e);
                }
            }
        } catch (Exception e) {
            Log.error("Error running completion phase", e);
        } finally {
            if (dm != null) {
                dm.close();
            }
        }
    }

    private void runStatusPollingPhase(String accessToken) {
        Log.debug("Polling status for OCR requests stuck in REQUEST_SENT ...");
        DatabaseManager dm = null;
        try {
            dm = new DatabaseManager();
            new OcrStatusPollingTask().run(accessToken, dm);
        } catch (Exception e) {
            Log.error("Error running status polling phase", e);
        } finally {
            if (dm != null) {
                dm.close();
            }
        }
    }
}
