package com.dataserve.ocr.util;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

public class Log {
	
	private Log() {}

	private static Logger log = LogManager.getLogger("Log");
	
	public static void info(String msg) {
		log.info(msg);
	}
	
	public static void debug(String msg) {
		log.debug(msg);
	}
	
	public static void warn(String msg) {
		log.warn(msg);
	}
	
	public static void warn(String msg, Throwable e) {
		log.warn(msg, e);
	}
	
	public static void error(String msg) {
		log.error(msg);
	}
	
	public static void error(String msg, Throwable e) {
		log.error(msg, e);
	}
	
}
