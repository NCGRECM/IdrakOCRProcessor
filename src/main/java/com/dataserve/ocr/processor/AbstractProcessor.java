package com.dataserve.ocr.processor;

import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;

public abstract class AbstractProcessor implements Processor {

	protected static String getTempPath() {
		String path = Config.getOCRTempPath();
		if (path == null || path.trim().equals("")) {
			Log.error("Temp files path must be configured in application.properties");
		}
		return path;
	}

}
