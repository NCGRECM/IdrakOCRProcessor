package com.dataserve.ocr.exception;

/** Errors connecting to / reading from the IBM MQ OCR.FileStatusMahfuthat queue. */
public class MqException extends Exception {

	private static final long serialVersionUID = 1L;

	public MqException(String message) {
		super(message);
	}

	public MqException(String message, Throwable cause) {
		super(message, cause);
	}
}
