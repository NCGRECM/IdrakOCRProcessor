package com.dataserve.ocr.exception;

/** Errors talking to the client's FileNet CMIS repository (OCRIdark folder). */
public class CmisException extends Exception {

	private static final long serialVersionUID = 1L;

	public CmisException(String message) {
		super(message);
	}

	public CmisException(String message, Throwable cause) {
		super(message, cause);
	}
}
