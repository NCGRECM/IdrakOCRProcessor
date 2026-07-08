package com.dataserve.ocr.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import com.dataserve.ocr.bean.DocumentBean;
import com.dataserve.ocr.database.DatabaseManager;
import com.dataserve.ocr.exception.DatabaseException;
import com.dataserve.ocr.idrak.IdrakAuthClient;
import com.dataserve.ocr.idrak.IdrakTokenResponse;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;

public class StarterTask extends TimerTask {
	private static final BlockingQueue<Callable<DocumentBean>> queue = new ArrayBlockingQueue<Callable<DocumentBean>>(Config.getBatchSize());

	@Override
	public void run() {
		Log.debug("OCR Processor Started");
		DatabaseManager dm = null;
		ThreadPoolExecutor executor = null;
		try {
			Log.debug("Logging in to Idrak OCR API ...");
			IdrakTokenResponse tokenResponse = new IdrakAuthClient().login();
			Log.debug("Idrak OCR API login succeeded. Token expires in " + tokenResponse.getExpiresIn() + " seconds");
			Log.debug("Idrak OCR API granted scope: " + tokenResponse.getScope());

			dm = new DatabaseManager();
			
			Log.debug("Initiating executor ... ");
			executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Config.getNumberOfProcessingThreads());
			
			while (dm.haveMoreDocuments()) {
				queue.clear();
				Log.debug("Fetching documents ... ");
				List<DocumentBean> docIds = dm.fetchDocuments();
				
				Log.debug("Queueing OCR tasks ...");
				for (DocumentBean docId : docIds) {
					queue.add(new OCRTask(docId));
				}
	
				Log.debug("Starting OCR process of " + queue.size() + " documents ...");
				List<Future<DocumentBean>> futures = new ArrayList<>();
				for (Callable<DocumentBean> call : queue) {
					futures.add(executor.submit(call));		
				}
				
//				Log.debug("Updaging database with processing results ...");
				if (futures != null && futures.size() > 0) {
					List<DocumentBean> results = new ArrayList<DocumentBean>();
					for (Future<DocumentBean> future : futures) {
						results.add(future.get());
					}
//					updateStatus(results);
				}
			}
			Log.debug("No more documents to process. Processing documents completed");
		} catch (DatabaseException e) {
			Log.error("Error accessing database", e);
		} catch (Exception e) {
			Log.error("Error running starter task", e);
		} finally {
			dm.close();
			if (executor != null) {
				executor.shutdown();
			}
		}
	}

	private void updateStatus(List<DocumentBean> results) throws DatabaseException {
		DatabaseManager dm = new DatabaseManager();
		for (DocumentBean bean : results) {
			try {
				dm.updateTable(bean);
				dm.logAudit(bean);
			} catch (DatabaseException e) {
				Log.error("Error updating table for document " + bean.getDocumentId(), e);
			}
		}
		dm.close();
	}

}
