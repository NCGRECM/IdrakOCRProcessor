package com.dataserve.ocr.mq;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dataserve.ocr.exception.MqException;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;
import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;

/**
 * Drains the OCR.FileStatusMahfuthat queue (IBM MQ, queue manager APPLN01T, channel APP.MAHFUTHAT)
 * each batch run - a durable queue, so connect/pull/disconnect each time loses nothing.
 *
 * Uses the classic (non-JMS) com.ibm.mq base classes. mq-user/mq-password come from
 * config.properties. drainQueue() runs the connect+drain work on a daemon worker thread bounded by
 * Config.getMqConnectTimeoutSeconds(), since a dead channel can otherwise hang connect/get forever
 * with no exception or log line.
 */
public class OcrMqConsumer {

    /** Connects, pulls every currently-available message off the queue (non-blocking), and disconnects - bounded by a timeout so a dead channel can't hang the batch run. */
    public List<OcrCompletionNotification> drainQueue() throws MqException {
        final int timeoutSeconds = Config.getMqConnectTimeoutSeconds();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ocr-mq-drain");
            t.setDaemon(true); // never block JVM shutdown even if this worker stays stuck forever
            return t;
        });
        try {
            Future<List<OcrCompletionNotification>> future = executor.submit(
                    (Callable<List<OcrCompletionNotification>>) this::drainQueueBlocking);
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            String msg = "Timed out after " + timeoutSeconds + "s waiting for MQ connect/drain on "
                    + Config.getMqQueueName() + " (host=" + Config.getMqHost() + ":" + Config.getMqPort()
                    + ") - the port may be open at TCP level but the channel/listener isn't answering the "
                    + "MQ handshake. Giving up for this run - will retry next scheduled run.";
            Log.mqError(msg, e);
            Log.error("OcrMqConsumer: " + msg, e);
            throw new MqException(msg, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof MqException) {
                throw (MqException) cause;
            }
            Log.mqError("Error draining " + Config.getMqQueueName(), cause);
            throw new MqException("Error draining OCR.FileStatusMahfuthat queue", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MqException("Interrupted while waiting for MQ drain", e);
        } finally {
            executor.shutdownNow(); // best-effort only - can't force-unblock a stuck native socket read
        }
    }

    /** The actual connect/open/get-loop/disconnect work, run on its own thread by drainQueue() above so it can be bounded by a timeout. */
    private List<OcrCompletionNotification> drainQueueBlocking() throws MqException {
        List<OcrCompletionNotification> notifications = new ArrayList<>();
        MQQueueManager queueManager = null;
        MQQueue queue = null;
        try {
            Log.debug("OcrMqConsumer: connecting to " + Config.getMqHost() + ":" + Config.getMqPort()
                    + " qmgr=" + Config.getMqQueueManager() + " channel=" + Config.getMqChannel());
            Log.mqInfo("Connecting to " + Config.getMqQueueName() + " (host=" + Config.getMqHost() + ":" + Config.getMqPort()
                    + " qmgr=" + Config.getMqQueueManager() + " channel=" + Config.getMqChannel() + ") ...");

            java.util.Hashtable<String, Object> connectionProperties = new java.util.Hashtable<>();
            connectionProperties.put(CMQC.HOST_NAME_PROPERTY, Config.getMqHost());
            connectionProperties.put(CMQC.PORT_PROPERTY, Config.getMqPort());
            connectionProperties.put(CMQC.CHANNEL_PROPERTY, Config.getMqChannel());
            connectionProperties.put(CMQC.USER_ID_PROPERTY, Config.getMqUser());
            connectionProperties.put(CMQC.PASSWORD_PROPERTY, Config.getMqPassword());

            queueManager = new MQQueueManager(Config.getMqQueueManager(), connectionProperties);
            int openOptions = CMQC.MQOO_INPUT_AS_Q_DEF | CMQC.MQOO_FAIL_IF_QUIESCING;
            queue = queueManager.accessQueue(Config.getMqQueueName(), openOptions);

            // CONFIRMS an actual reply came back from the queue manager - connect() and accessQueue()
            // both completed successfully, so this is the real MQ handshake succeeding, not just a port
            // being open at the TCP level. Logged UNCONDITIONALLY (unlike "Drained N" below, which only
            // logs when N>0) so a quiet/empty queue still leaves proof in this file that the connection
            // itself is healthy.
            Log.mqInfo("Connected successfully to " + Config.getMqQueueName() + " - queue manager replied, queue opened");

            MQGetMessageOptions gmo = new MQGetMessageOptions();
            gmo.options = CMQC.MQGMO_NO_WAIT | CMQC.MQGMO_FAIL_IF_QUIESCING | CMQC.MQGMO_CONVERT;

            while (true) {
                MQMessage message = new MQMessage();
                try {
                    queue.get(message, gmo);
                } catch (MQException mqe) {
                    if (mqe.reasonCode == CMQC.MQRC_NO_MSG_AVAILABLE) {
                        break; // queue drained
                    }
                    throw mqe;
                }

                byte[] body = new byte[message.getMessageLength()];
                message.readFully(body);
                String payload = new String(body, "UTF-8");
                Log.debug("OcrMqConsumer: received message, length=" + body.length);
                Log.mqInfo("Received message on " + Config.getMqQueueName() + " (length=" + body.length + " bytes): " + payload);

                OcrCompletionNotification notification = parse(payload);
                if (notification != null) {
                    notifications.add(notification);
                    Log.mqInfo("Parsed notification: " + notification);
                } else {
                    Log.warn("OcrMqConsumer: could not parse message, skipping: " + payload);
                    Log.mqWarn("Could not parse message, skipping: " + payload);
                }
            }

            Log.debug("OcrMqConsumer: drained " + notifications.size() + " notification(s)");
            if (notifications.size() > 0) {
                Log.mqInfo("Drained " + notifications.size() + " notification(s) from " + Config.getMqQueueName());
            }
            return notifications;
        } catch (Exception e) {
            Log.mqError("Error draining " + Config.getMqQueueName(), e);
            throw new MqException("Error draining OCR.FileStatusMahfuthat queue", e);
        } finally {
            if (queue != null) {
                try { queue.close(); } catch (Exception e) { Log.warn("Error closing MQ queue", e); }
            }
            if (queueManager != null) {
                try { queueManager.disconnect(); } catch (Exception e) { Log.warn("Error disconnecting MQ queue manager", e); }
            }
        }
    }

    private OcrCompletionNotification parse(String xmlOrJson) {
        // FileList can contain multiple FileInfo entries in principle, but in our flow we submit one
        // file per RequestId, so we only expect (and only handle) a single FileInfo per notification.
        String requestId = extractTag(xmlOrJson, "RequestId");
        String requestFileNetId = extractTag(xmlOrJson, "RequestFileNetId");
        String responseFileNetId = extractTag(xmlOrJson, "ResponseFileNetId");
        String fileStatusStr = extractTag(xmlOrJson, "FileStatus");
        // The two sample docs (FuncId 53010200 vs 53010201) use different field names for the same
        // thing - "ResponseDate" in one sample/the XSD table, "ResponseDt" in the other. Try both.
        String responseDate = extractTag(xmlOrJson, "ResponseDt");
        if (responseDate == null) {
            responseDate = extractTag(xmlOrJson, "ResponseDate");
        }

        if (requestId == null) {
            return null;
        }

        OcrCompletionNotification notification = new OcrCompletionNotification();
        notification.setRequestId(requestId);
        notification.setRequestFileNetId(requestFileNetId);
        notification.setResponseFileNetId(responseFileNetId);
        notification.setResponseDate(responseDate);
        if (fileStatusStr != null) {
            try {
                notification.setFileStatus(Integer.parseInt(fileStatusStr.trim()));
            } catch (NumberFormatException e) {
                Log.warn("OcrMqConsumer: non-numeric FileStatus '" + fileStatusStr + "'");
            }
        }
        return notification;
    }

    private String extractTag(String text, String tagName) {
        Pattern xmlPattern = Pattern.compile("<(?:\\w+:)?" + tagName + "[^>]*>([^<]*)</(?:\\w+:)?" + tagName + ">");
        Matcher xmlMatcher = xmlPattern.matcher(text);
        if (xmlMatcher.find()) {
            return xmlMatcher.group(1);
        }
        Pattern jsonPattern = Pattern.compile("\"" + tagName + "\"\\s*:\\s*\"?([^\",}]*)\"?");
        Matcher jsonMatcher = jsonPattern.matcher(text);
        if (jsonMatcher.find()) {
            String value = jsonMatcher.group(1);
            // JSON null comes through as the literal text "null" here, not an actual null -
            // treat it as null rather than storing the 4-char string.
            if ("null".equals(value)) {
                return null;
            }
            return value;
        }
        return null;
    }
}
