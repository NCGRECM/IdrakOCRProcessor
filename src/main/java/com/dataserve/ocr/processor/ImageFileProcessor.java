package com.dataserve.ocr.processor;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

import com.dataserve.ocr.exception.OCRException;
import com.dataserve.ocr.util.Config;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

public class ImageFileProcessor extends AbstractProcessor {

	@Override
	public File processFile(File sourceFile) throws Exception {
		try {
			ITesseract tesseract = new Tesseract();
			tesseract.setDatapath(Config.getOCRDataPath());
			tesseract.setLanguage(Config.getOCRLanguages());
			List<ITesseract.RenderedFormat> formats = new LinkedList<>();
			formats.add(ITesseract.RenderedFormat.PDF);

			String targetName = sourceFile.getName();//.substring(0, sourceFile.getName().indexOf("."));
			tesseract.createDocumentsWithResults(sourceFile.getAbsolutePath(), targetName, formats, ITessAPI.TessPageIteratorLevel.RIL_WORD);
			
			File pdfFile = new File(targetName + ".pdf");
			if (pdfFile == null || !pdfFile.exists()) {
				throw new OCRException("File " + targetName + ".pdf was not generated successfully!");
			}
			
			return pdfFile;
		} catch (TesseractException e) {
			throw new OCRException("Error parsing image file", e);
		}
	}
	
	public StringBuilder processFileAsText(File sourceFile) throws Exception {
		try {
			ITesseract tesseract = new Tesseract();
			tesseract.setDatapath(Config.getOCRDataPath());
			tesseract.setLanguage(Config.getOCRLanguages());
			List<ITesseract.RenderedFormat> formats = new LinkedList<>();
			formats.add(ITesseract.RenderedFormat.TEXT);
			StringBuilder content = new StringBuilder();
			content.append(tesseract.doOCR(sourceFile));
			return content;
		} catch (TesseractException e) {
			throw new OCRException("Error parsing image file " + sourceFile.getAbsolutePath(), e);
		}
		
	}

}
