package com.dataserve.ocr.exception;

public class OCRException extends Exception {

	private static final long serialVersionUID = 9105044723347524642L;

	public OCRException() { }

	public OCRException(String message) {
		super(message);
	}

	public OCRException(Throwable cause) {
		super(cause);
	}

	public OCRException(String message, Throwable cause) {
		super(message, cause);
	}

	public OCRException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

}
