package com.dataserve.ocr.processor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import com.dataserve.ocr.exception.OCRException;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

public class PDFFileProcessor extends AbstractProcessor {

	private static final float DEFAULT_RESOLUTION = 200;
	private static final float MINIMUM_RESOLUTIN = 100;
	private static final float MAXIMUM_RESOLUTION = 400;

	private static final long DEFAULT_MEMORY_SETTING = 5242880L;
	private final String TEMP_PATH;
	
	public PDFFileProcessor() {
		TEMP_PATH = getTempPath() + (getTempPath().endsWith(File.separator) ? "" : File.separator) + Thread.currentThread().getName() + File.separator;
		File tempPath = new File(TEMP_PATH);
		if (!tempPath.exists()) {
			tempPath.mkdirs();
		}
	}
	
	public File processFile(File sourceFile) throws Exception {	
		File resultFile = null;
		String originalFileName = sourceFile.getName();
		try (FileInputStream fis = null; PDDocument document = PDDocument.load(Files.readAllBytes(sourceFile.toPath()))) {	
			
			// Split the pdf file into separate pages, convert every page into an image, and
			// save them.
			BufferedImage[] fileNames = splitPDFIntoImageFiles(document);
			if(fileNames !=null) {
				// Create an array of the output file names
				String[] outputbases = prepareTargetFiles(document);

				// Convert split images into pdf's files, and save them with the suggested names
				parseOCR(fileNames, outputbases);

				// Merge created pdf files into one output file
				resultFile = mergeResultFiles(outputbases, originalFileName);
				
				// Delete temp files
				cleanup(outputbases);
			}else {
				Log.debug(Thread.currentThread().getName() + "Failed split PDF File Into Image Files " + sourceFile.getAbsolutePath() );

			}
			
			
			return resultFile;
		} catch (InvalidPasswordException ipe) {
			Log.debug(Thread.currentThread().getName() + "InvalidPasswordException Error process  file " + sourceFile.getAbsolutePath() );
			Log.debug(Thread.currentThread().getName() + ipe.getMessage() );
			throw new OCRException("Failed to load PDDocumet due to the following error: ", ipe);
		} catch (IOException ioe) {
			Log.debug(Thread.currentThread().getName() + "IOException Error process  file " + sourceFile.getAbsolutePath() );
			Log.debug(Thread.currentThread().getName() + ioe.getMessage() );
			throw new OCRException("Failed to merge files due to the following error: ", ioe);
		} catch (TesseractException te) {
			Log.debug(Thread.currentThread().getName() + "TesseractException Error process  file " + sourceFile.getAbsolutePath() );
			Log.debug(Thread.currentThread().getName() + te.getMessage() );
			throw new OCRException("OCR Failed to process file due to the following error: ", te);
		} catch (Exception e) {
			throw new OCRException(e.getMessage(), e);
		}
	}
	
	public StringBuilder processFileAsText(File sourceFile) throws Exception {	
		try (FileInputStream fis = null; PDDocument document = PDDocument.load(Files.readAllBytes(sourceFile.toPath()))) {	
			BufferedImage[] fileNames = splitPDFIntoImageFiles(document);
			if(fileNames !=null) {
				StringBuilder content = parseOCR(fileNames,sourceFile);
				//System.out.println(content);
				return content;
			}
			
			return null;
		} catch (InvalidPasswordException ipe) {
			throw new OCRException("Failed to load PDDocumet due to the following error: ", ipe);
		} catch (IOException ioe) {
			throw new OCRException("Failed to merge files due to the following error: ", ioe);
		} catch (TesseractException te) {
			throw new OCRException("OCR Failed to process file due to the following error: ", te);
		} catch (Exception e) {
			throw new OCRException(e.getMessage(), e);
		}
	}

	private void cleanup(String[] outputBases) {
		for (String fileName : outputBases) {
			File file = new File(fileName);
			try {
				Files.deleteIfExists(file.toPath());
			} catch (IOException e) {
				Log.warn("Failed to delete file '" + file.getAbsolutePath() + "'", e);
			}
		}
	}

	private BufferedImage[] splitPDFIntoImageFiles(PDDocument document)  {
		BufferedImage[] imagesOfPdf= null;
		try {
			imagesOfPdf = new BufferedImage[document.getNumberOfPages()];
			PDFRenderer pdfRenderer = new PDFRenderer(document);
	
			for (int page = 0; page < document.getNumberOfPages(); ++page) {
				BufferedImage bim = pdfRenderer.renderImageWithDPI(page, getResolution(), ImageType.RGB);
				imagesOfPdf[page] = bim;
			}
		}
		catch (Exception ex) {
			Log.error("Error split PDF file Into Image Files  ", ex);

		}

		return imagesOfPdf;
	}

	private String[] prepareTargetFiles(PDDocument document) throws IOException, OCRException {
		String[] outputbases = new String[document.getNumberOfPages()];

		for (int y = 0; y < document.getNumberOfPages(); y++) {
			Path tempResultFilePath;
			tempResultFilePath = Paths.get(TEMP_PATH + "temp_" + y + ".pdf");
			outputbases[y] = tempResultFilePath.toFile().getAbsolutePath();
		}

		return outputbases;
	}

	private void parseOCR(BufferedImage[] sourceFiles, String[] targetFiles) throws TesseractException {
		List<ITesseract.RenderedFormat> formats = new LinkedList<>();
		formats.add(ITesseract.RenderedFormat.PDF);
		ITesseract instance = new Tesseract();
		instance.setDatapath(Config.getOCRDataPath());
		instance.setLanguage(Config.getOCRLanguages());
		instance.createDocumentsWithResults(sourceFiles, targetFiles, targetFiles, formats, ITessAPI.TessPageIteratorLevel.RIL_WORD);
	}
	
	private StringBuilder parseOCR(BufferedImage[] sourceFiles,File sourceFile) throws TesseractException {
		List<ITesseract.RenderedFormat> formats = new LinkedList<>();
		formats.add(ITesseract.RenderedFormat.TEXT);
		ITesseract instance = new Tesseract();
		instance.setDatapath(Config.getOCRDataPath());
		instance.setLanguage(Config.getOCRLanguages());
		StringBuilder content = new StringBuilder();
		
		for (int i = 0; i < sourceFiles.length; i++) {
			BufferedImage image = sourceFiles[i];
			content.append(instance.doOCR(image));
		}
		return content;
	}

	private File mergeResultFiles(String[] outputFileNames, String originalFileName) throws OCRException {
		PDFMergerUtility pdfMmerger = new PDFMergerUtility();
		File resultFile = null;
		try {
			resultFile = Paths.get(TEMP_PATH + originalFileName).toFile();
			pdfMmerger.setDestinationFileName(resultFile.getAbsolutePath());
			for (int y = 0; y < outputFileNames.length; y++) {
				pdfMmerger.addSource(new File(outputFileNames[y]) + ".pdf");
			}
//			pdfMmerger.addSource(new File(outputFileNames[0]) + ".pdf");
			
			Long maxMemToUse = Long.valueOf(Config.getOCRMaxMemory());
			if (maxMemToUse == null) {
				maxMemToUse = DEFAULT_MEMORY_SETTING;
			}
			MemoryUsageSetting mus = MemoryUsageSetting.setupMixed(maxMemToUse);
			pdfMmerger.mergeDocuments(mus);
			return resultFile;
		} catch (Exception e) {
			throw new OCRException("Error merging result files", e);
		}
	}

	private float getResolution() {
		String p = Config.getOCRResolution();
		if (p == null || p.trim().equals("")) {
			return DEFAULT_RESOLUTION;
		}
		try {
			float r = Float.parseFloat(p);
			if (r < MINIMUM_RESOLUTIN) {
				return MINIMUM_RESOLUTIN;
			}
			if (r > MAXIMUM_RESOLUTION) {
				return MAXIMUM_RESOLUTION;
			}
			return r;
		} catch (NumberFormatException e) {
			return DEFAULT_RESOLUTION;
		}
	}

}
