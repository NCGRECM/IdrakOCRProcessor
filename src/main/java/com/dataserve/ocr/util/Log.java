package com.dataserve.ocr.util;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

public class Log {

    private Log() {}

    private static Logger log = LogManager.getLogger("Log");

    // Separate logger category ("MQ") so MQ arrivals/processing can also be routed to their own
    // log file (see log4j.xml's "mqFile" appender + <category name="MQ">) - lets you check just
    // what came in overnight on OCR.FileStatusMahfuthat without wading through the full app log.
    private static Logger mqLog = LogManager.getLogger("MQ");

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

    public static void mqInfo(String msg) {
        mqLog.info(msg);
    }

    public static void mqWarn(String msg) {
        mqLog.warn(msg);
    }

    public static void mqError(String msg, Throwable e) {
        mqLog.error(msg, e);
    }

}
