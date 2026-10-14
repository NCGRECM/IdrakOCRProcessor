package com.dataserve.ocr.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Config {

    private static Properties props = new Properties();

    static {
        try (InputStream is = Config.class.getClassLoader().getResourceAsStream("config.properties")) {
            props.load(is);
        } catch (IOException e) {
            Log.error("Failed to load config.properties file", e);
        }
    }

    public static String getOCRDataPath() {
        return props.getProperty("ocr-data-path");
    }

    public static String getOCRLanguages() {
        return props.getProperty("ocr-languages");
    }

    public static String getOCRMaxMemory() {
        return props.getProperty("ocr-max-memory-used-for-merging-img");
    }

    public static String getOCRResolution() {
        return props.getProperty("ocr-resolution");
    }

    public static String getOCRTempPath() {
        return props.getProperty("ocr-temp-path");
    }

    public static int getBatchSize() {
        return Integer.parseInt(props.getProperty("database-fetch-batch-size"));
    }

    public static int getNumberOfProcessingThreads() {
        return Integer.parseInt(props.getProperty("number-of-processing-threads"));
    }

    public static String getDatabaseUsername() {
        return props.getProperty("database-username");
    }

    public static String getDatabasePassword() {
        return props.getProperty("database-password");
    }

    public static String getDatabaseServerName() {
        return props.getProperty("database-server-name");
    }

    public static int getDatabasePortNumber() {
        return Integer.parseInt(props.getProperty("database-port-number"));
    }

    public static String getDatabaseName() {
        return props.getProperty("database-name");
    }

    public static boolean isSSLEncryptionEnabled() {
        return ("1".equals(props.getProperty("database-ssl-encryption-enabled")));
    }

    public static String getContentEngineURI() {
        return props.getProperty("content-engine-uri");
    }

    public static String getContentEngineUsername() {
        return props.getProperty("content-engine-username");
    }

    public static String getContentEnginePassword() {
        return props.getProperty("content-engine-password");
    }

    public static String getObjectStoreName() {
        return props.getProperty("content-engine-object-store-name");
    }

    public static String getContentEngineStanza() {
        return props.getProperty("content-engine-stanza");
    }

    public static String getOutputType() {
        return props.getProperty("output-type");
    }
    public static String getExculdedFetchDocumentStatusForOCR() {
        return props.getProperty("exculded_fetch_document_status_for_OCR");
    }

    public static String getIdrakTokenUrl() {
        return props.getProperty("idrak-token-url");
    }

    public static String getIdrakClientId() {
        return props.getProperty("idrak-client-id");
    }

    public static String getIdrakClientSecret() {
        return props.getProperty("idrak-client-secret");
    }

    public static String getIdrakGrantType() {
        return props.getProperty("idrak-grant-type");
    }

    public static String getIdrakScope() {
        return props.getProperty("idrak-scope");
    }

    public static String getIdrakCookie() {
        return props.getProperty("idrak-cookie");
    }

    public static int getIdrakConnectionTimeout() {
        return Integer.parseInt(props.getProperty("idrak-connection-timeout"));
    }

    public static int getIdrakReadTimeout() {
        return Integer.parseInt(props.getProperty("idrak-read-timeout"));
    }

    /**
     * Minutes a request must sit in STATUS_REQUEST_SENT before OcrStatusPollingTask will bother
     * calling checkRequestStatus for it (per the client's own guidance - see IdrakOcrSoapClient).
     * Add "ocr-status-polling-min-age-minutes=60" (or whatever value is agreed) to config.properties;
     * defaults to 60 if not set so a missing property doesn't break startup.
     */
    public static int getOcrStatusPollingMinAgeMinutes() {
        String value = props.getProperty("ocr-status-polling-min-age-minutes");
        return value == null ? 60 : Integer.parseInt(value.trim());
    }

    // ---- Idrak OCR SOAP (IdrakOCRFileRequest / Check Request Status) ----

    public static String getIdrakOcrRequestUrl() {
        return props.getProperty("idrak-ocr-request-url");
    }

    public static String getIdrakOcrStatusUrl() {
        return props.getProperty("idrak-ocr-status-url");
    }

    public static String getIdrakOcrFuncIdRequest() {
        return props.getProperty("idrak-ocr-func-id-request");
    }

    public static String getIdrakOcrFuncIdStatus() {
        return props.getProperty("idrak-ocr-func-id-status");
    }

    public static String getIdrakOcrScid() {
        return props.getProperty("idrak-ocr-scid");
    }

    public static String getIdrakOcrServiceId() {
        return props.getProperty("idrak-ocr-service-id");
    }

    public static String getIdrakOcrSrcSystem() {
        return props.getProperty("idrak-ocr-src-system");
    }

    public static String getIdrakOcrMsgVersion() {
        return props.getProperty("idrak-ocr-msg-version");
    }

    // ---- Client CMIS repository (OCRIdark folder) ----

    public static String getCmisRepositoryUrl() {
        return props.getProperty("cmis-repository-url");
    }

    public static String getCmisRepositoryId() {
        return props.getProperty("cmis-repository-id");
    }

    public static String getCmisFolderName() {
        return props.getProperty("cmis-folder-name");
    }

    public static String getCmisFolderId() {
        return props.getProperty("cmis-folder-id");
    }

    public static String getCmisClassId() {
        return props.getProperty("cmis-class-id");
    }

    public static String getCmisClassName() {
        return props.getProperty("cmis-class-name");
    }

    public static String getCmisUsername() {
        return props.getProperty("cmis-username");
    }

    public static String getCmisPassword() {
        return props.getProperty("cmis-password");
    }

    // ---- Encryption keys ----

    public static String getIdrakPublicKeyPath() {
        return props.getProperty("idrak-public-key-path");
    }

    public static String getOurPrivateKeyPath() {
        return props.getProperty("our-private-key-path");
    }

    public static String getOurPublicKeyPath() {
        return props.getProperty("our-public-key-path");
    }

    // ---- IBM MQ (async OCR completion notification) ----

    public static String getMqHost() {
        return props.getProperty("mq-host");
    }

    public static int getMqPort() {
        return Integer.parseInt(props.getProperty("mq-port"));
    }

    public static String getMqQueueManager() {
        return props.getProperty("mq-queue-manager");
    }

    public static String getMqChannel() {
        return props.getProperty("mq-channel");
    }

    public static String getMqQueueName() {
        return props.getProperty("mq-queue-name");
    }

    public static String getMqUser() {
        return props.getProperty("mq-user");
    }

    public static String getMqPassword() {
        return props.getProperty("mq-password");
    }

    /** Bounds how long OcrMqConsumer.drainQueue() waits before giving up, instead of hanging the batch run indefinitely. Set via mq-connect-timeout-seconds; defaults to 20. */
    public static int getMqConnectTimeoutSeconds() {
        String value = props.getProperty("mq-connect-timeout-seconds");
        return value == null ? 20 : Integer.parseInt(value.trim());
    }
}
