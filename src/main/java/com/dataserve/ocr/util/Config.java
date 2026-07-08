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
}
