package com.dataserve.ocr.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.dataserve.ocr.bean.DocumentBean;
import com.dataserve.ocr.exception.DatabaseException;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.microsoft.sqlserver.jdbc.SQLServerException;

public class DatabaseManager {
	private Connection con = null;
	
	public DatabaseManager() throws DatabaseException {
/*        // Create a sql server data source object. 
        SQLServerDataSource ds = new SQLServerDataSource();
        
//         If you want to use sql server account authentication.
        ds.setIntegratedSecurity(false);
        ds.setUser(Config.getDatabaseUsername());
        ds.setPassword(Config.getDatabasePassword());
                    
        // Set ds server name or ip.
        ds.setServerName(Config.getDatabaseServerName());
        // Set sql server listening port number.
        ds.setPortNumber(Config.getDatabasePortNumber()); 
        // Set the database name.
        ds.setDatabaseName(Config.getDatabaseName());
        
//        if (Config.isSSLEncryptionEnabled()) {
//        	ds.setEncrypt(true);
//        	ds.setTrustServerCertificate(true);
//        }
*/        
        // Get connection
        try {
        	String DB_CONN_STRING = "jdbc:sqlserver://" + Config.getDatabaseServerName() + ":" + Config.getDatabasePortNumber()
			+ ";databaseName="+Config.getDatabaseName() ;
        	
//        	Log.info("DB_CONN_STRING  :  "+ DB_CONN_STRING);
        	con = DriverManager.getConnection(DB_CONN_STRING,Config.getDatabaseUsername(),Config.getDatabasePassword());
        	con.setAutoCommit(true);
//			con = ds.getConnection();
		} catch (SQLServerException e) {
			throw new DatabaseException("Error getting connection to database", e);
		} catch (Exception e) {
			throw new DatabaseException("Error getting connection to database", e);
		}
	}

	public List<DocumentBean> fetchDocuments() throws DatabaseException {
		List<DocumentBean> documentIds = new ArrayList<DocumentBean>();
//		String query = "SELECT TOP " + Config.getBatchSize() + " DOCUMENT_ID, DOCUMENT_CLASS, FILE_ID FROM DMS_FILES WHERE OCR_STATUS NOT IN (4, 5, 6)";
		String query = "SELECT TOP " + Config.getBatchSize() + " DOCUMENT_ID, DOCUMENT_CLASS, FILE_ID FROM DMS_FILES WHERE OCR_STATUS NOT IN ("+Config.getExculdedFetchDocumentStatusForOCR()+")";
		StringBuilder sb = new StringBuilder();
		try (PreparedStatement st = con.prepareStatement(query); ResultSet rs = st.executeQuery()) {
			while (rs.next()) {
				String docId = rs.getString("DOCUMENT_ID");
				String docClass = rs.getString("DOCUMENT_CLASS");
				DocumentBean db = new DocumentBean();
				db.setDocumentId(docId);
				db.setDocumentClass(docClass);
				db.setFileId(rs.getLong("FILE_ID"));
				documentIds.add(db);
				sb.append("'" + docId + "',");
			}
		} catch (SQLException e) {
			throw new DatabaseException("Error fetching document ids", e);
		}
		Log.debug("Number of fetched documents: " + documentIds.size());
		return documentIds;
	}

	public boolean haveMoreDocuments()  throws DatabaseException{
//		String query = "SELECT COUNT(*) FROM DMS_FILES WHERE OCR_STATUS  NOT IN (4, 5, 6)";
		String query = "SELECT COUNT(*) FROM DMS_FILES WHERE OCR_STATUS  NOT IN ("+Config.getExculdedFetchDocumentStatusForOCR()+")";
		try (PreparedStatement st = con.prepareStatement(query); ResultSet rs = st.executeQuery()) {
			if (rs.next()) {
				int docsToProcess = rs.getInt(1);
				Log.debug("Number of documents to process: " + docsToProcess);
				return docsToProcess > 0;
			}
		} catch (SQLException e) {
			throw new DatabaseException("Error fetching document ids", e);
		}
		return false;
	}

	public void updateTable(DocumentBean bean)  throws DatabaseException{
		String sql = "UPDATE DMS_FILES SET OCR_STATUS = ?, OCR_DATE = ? WHERE DOCUMENT_ID = ?";
		PreparedStatement st = null;
		try {
			st = con.prepareStatement(sql);
			st.setInt(1, bean.getStatus());
			st.setTimestamp(2, new java.sql.Timestamp(new Date().getTime()));
			st.setString(3, bean.getDocumentId());
			st.executeUpdate();
			if (bean.getStatus() == 5) {
				sql = "UPDATE DMS_FILES SET MODIFIED_DATE = OCR_DATE, DOCUMENT_ID = ? WHERE DOCUMENT_ID = ?";
				st = con.prepareStatement(sql);
				st.setString(1, bean.getNewDocumentId());
				st.setString(2, bean.getDocumentId());
				st.executeUpdate();
			}
		} catch (Exception e) {
			throw new DatabaseException("Error updating table for bean " + bean.toString(), e);
		} finally {
			try {
				st.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public void logAudit(DocumentBean bean) throws DatabaseException {
		String sql = "INSERT INTO OCR_AUDIT (DATE, DOCUMENT_ID, DOCUMENT_CLASS, FILE_ID, FILE_NAME, STATUS_ID, ERROR_MESSAGE) VALUES (?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement st = con.prepareStatement(sql)) {
			st.setTimestamp(1, new java.sql.Timestamp(new Date().getTime()));
			
			if (bean.getDocumentId() == null) {
				st.setNull(2, Types.VARCHAR);
			} else {
				st.setString(2, bean.getDocumentId());
			}
			
			if (bean.getDocumentClass() == null) {
				st.setNull(3, Types.VARCHAR);
			} else {
				st.setString(3, bean.getDocumentClass());
			}
			
			if (bean.getFileId() == null) {
				st.setNull(4, Types.BIGINT);
			} else {
				st.setLong(4, bean.getFileId());
			}
			
			if (bean.getFileName() == null) {
				st.setNull(5, Types.NVARCHAR);
			} else {
				st.setNString(5, bean.getFileName());
			}
			
			st.setInt(6, bean.getStatus());
			
			if (bean.getErrorMassage() == null) {
				st.setNull(7, Types.VARCHAR);
			} else {
				st.setNString(7, bean.getErrorMassage());
			}
			
			st.executeUpdate();
		} catch (SQLException e) {
			throw new DatabaseException("Error updating audit for bean " + bean.toString(), e);
		}
	}

	public void close() {
		if (con != null) {
			try {
				con.close();
			} catch (SQLException e) {
				Log.warn("Error closing database connection.", e);
			}
		}
	}
	
}
