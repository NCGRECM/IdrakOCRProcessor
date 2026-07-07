package com.dataserve.ocr.processor;

import java.io.File;

public interface Processor {
	public File processFile(File sourceFile) throws Exception;
	public StringBuilder processFileAsText(File sourceFile) throws Exception;
	
}
