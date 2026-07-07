package com.dataserve.ocr.exception;

public class CMException extends Exception {

	private static final long serialVersionUID = 6506517586784031119L;

	public CMException() {}

	public CMException(String message) {
		super(message);
	}

	public CMException(Throwable cause) {
		super(cause);
	}

	public CMException(String message, Throwable cause) {
		super(message, cause);
	}

	public CMException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

}
