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
import com.dataserve.ocr.bean.OcrRequestBean;
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

    /** Updates DMS_FILES.OCR_STATUS/OCR_DATE for a document, called from the async MQ completion path. */
    public void finalizeDmsFilesDocument(String oldDocumentId, String newDocumentId, boolean success) throws DatabaseException {
        String sql = "UPDATE DMS_FILES SET OCR_STATUS = ?, OCR_DATE = ? WHERE DOCUMENT_ID = ?";
        try (PreparedStatement st = con.prepareStatement(sql)) {
            st.setInt(1, success ? DocumentBean.STATUS_SUCCESS : DocumentBean.STATUS_FAIL);
            st.setTimestamp(2, new java.sql.Timestamp(new Date().getTime()));
            st.setString(3, oldDocumentId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Error updating DMS_FILES OCR_STATUS for document " + oldDocumentId, e);
        }
        if (success && newDocumentId != null) {
            String renameSql = "UPDATE DMS_FILES SET MODIFIED_DATE = OCR_DATE, DOCUMENT_ID = ? WHERE DOCUMENT_ID = ?";
            try (PreparedStatement st = con.prepareStatement(renameSql)) {
                st.setString(1, newDocumentId);
                st.setString(2, oldDocumentId);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new DatabaseException("Error renaming DMS_FILES.DOCUMENT_ID from " + oldDocumentId + " to " + newDocumentId, e);
            }
        }
    }

    // ==================== OCR_REQUEST (Idrak integration tracking) ====================
    // See src/main/resources/db/migration_ocr_request.sql - run once against NCGR_MAHFOUZAT before deploying.

    public long insertOcrRequest(OcrRequestBean bean) throws DatabaseException {
        String sql = "INSERT INTO OCR_REQUEST (DOCUMENT_ID, DOCUMENT_CLASS, FILE_ID, FILE_NAME, REQUEST_ID, STATUS, CREATED_DATE) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement st = con.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            st.setString(1, bean.getDocumentId());
            st.setString(2, bean.getDocumentClass());
            if (bean.getFileId() == null) {
                st.setNull(3, Types.BIGINT);
            } else {
                st.setLong(3, bean.getFileId());
            }
            st.setString(4, bean.getFileName());
            st.setString(5, bean.getRequestId());
            st.setInt(6, bean.getStatus());
            st.setTimestamp(7, new java.sql.Timestamp(new Date().getTime()));
            st.executeUpdate();
            try (ResultSet keys = st.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            return -1;
        } catch (SQLException e) {
            throw new DatabaseException("Error inserting OCR_REQUEST for document " + bean.getDocumentId(), e);
        }
    }

    /** After we've uploaded the encrypted file to the client's CMIS and gotten its object id back. */
    public void markUploaded(String requestId, String requestFileNetId) throws DatabaseException {
        String sql = "UPDATE OCR_REQUEST SET STATUS = ?, REQUEST_FILENET_ID = ?, UPDATED_DATE = ? WHERE REQUEST_ID = ?";
        try (PreparedStatement st = con.prepareStatement(sql)) {
            st.setInt(1, OcrRequestBean.STATUS_UPLOADED);
            st.setString(2, requestFileNetId);
            st.setTimestamp(3, new java.sql.Timestamp(new Date().getTime()));
            st.setString(4, requestId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Error marking OCR_REQUEST uploaded for requestId " + requestId, e);
        }
    }

    /** After the IdrakOCRFileRequest SOAP call succeeded (ack only, not the OCR result). */
    public void markRequestSent(String requestId) throws DatabaseException {
        String sql = "UPDATE OCR_REQUEST SET STATUS = ?, UPDATED_DATE = ? WHERE REQUEST_ID = ?";
        try (PreparedStatement st = con.prepareStatement(sql)) {
            st.setInt(1, OcrRequestBean.STATUS_REQUEST_SENT);
            st.setTimestamp(2, new java.sql.Timestamp(new Date().getTime()));
            st.setString(3, requestId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Error marking OCR_REQUEST request-sent for requestId " + requestId, e);
        }
    }

    public void markFailed(String requestId, String errorMessage) throws DatabaseException {
        String sql = "UPDATE OCR_REQUEST SET STATUS = ?, ERROR_MESSAGE = ?, UPDATED_DATE = ? WHERE REQUEST_ID = ?";
        try (PreparedStatement st = con.prepareStatement(sql)) {
            st.setInt(1, OcrRequestBean.STATUS_FAILED);
            st.setString(2, errorMessage);
            st.setTimestamp(3, new java.sql.Timestamp(new Date().getTime()));
            st.setString(4, requestId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Error marking OCR_REQUEST failed for requestId " + requestId, e);
        }
    }

    /** After we fetched + decrypted the result and saved it as a new version of our own document. */
    public void markCompleted(String requestId, String responseFileNetId, int fileStatus, boolean success, String newDocumentId) throws DatabaseException {
        String sql = "UPDATE OCR_REQUEST SET STATUS = ?, RESPONSE_FILENET_ID = ?, FILE_STATUS = ?, NEW_DOCUMENT_ID = ?, UPDATED_DATE = ? WHERE REQUEST_ID = ?";
        try (PreparedStatement st = con.prepareStatement(sql)) {
            st.setInt(1, success ? OcrRequestBean.STATUS_COMPLETED_SUCCESS : OcrRequestBean.STATUS_COMPLETED_FAILED);
            st.setString(2, responseFileNetId);
            st.setInt(3, fileStatus);
            st.setString(4, newDocumentId);
            st.setTimestamp(5, new java.sql.Timestamp(new Date().getTime()));
            st.setString(6, requestId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Error marking OCR_REQUEST completed for requestId " + requestId, e);
        }
    }

    /**
     * Candidates for the status-polling fallback (see OcrStatusPollingTask): requests still sitting in
     * STATUS_REQUEST_SENT (ack'd by Idrak but no MQ completion notify received yet) and old enough
     * that it's worth spending a SOAP call to check - per the client's own guidance that
     * checkRequestStatus "should be used with files with no response for long time only", not polled
     * on every single run.
     */
    public List<OcrRequestBean> findRequestsAwaitingStatusPoll(int minAgeMinutes) throws DatabaseException {
        List<OcrRequestBean> result = new ArrayList<OcrRequestBean>();
        String sql = "SELECT * FROM OCR_REQUEST WHERE STATUS = ? AND CREATED_DATE <= DATEADD(MINUTE, ?, GETDATE())";
        try (PreparedStatement st = con.prepareStatement(sql)) {
            st.setInt(1, OcrRequestBean.STATUS_REQUEST_SENT);
            st.setInt(2, -minAgeMinutes);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    result.add(mapOcrRequestBean(rs));
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Error finding OCR_REQUEST rows awaiting status poll", e);
        }
        return result;
    }

    /**
     * Used by StarterTask before bothering to connect to MQ at all: if nothing is currently
     * uploaded/sent and awaiting an Idrak reply, there's nothing a drain could possibly find.
     */
    public boolean hasInFlightOcrRequests() throws DatabaseException {
        String sql = "SELECT COUNT(*) FROM OCR_REQUEST WHERE STATUS IN (?, ?)";
        try (PreparedStatement st = con.prepareStatement(sql)) {
            st.setInt(1, OcrRequestBean.STATUS_UPLOADED);
            st.setInt(2, OcrRequestBean.STATUS_REQUEST_SENT);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Error checking for in-flight OCR_REQUEST rows", e);
        }
        return false;
    }

    /** Looks up the tracking row by the RequestId we get back in an MQ completion notification. */
    public OcrRequestBean findByRequestId(String requestId) throws DatabaseException {
        String sql = "SELECT * FROM OCR_REQUEST WHERE REQUEST_ID = ?";
        try (PreparedStatement st = con.prepareStatement(sql)) {
            st.setString(1, requestId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return mapOcrRequestBean(rs);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Error finding OCR_REQUEST for requestId " + requestId, e);
        }
        return null;
    }

    private OcrRequestBean mapOcrRequestBean(ResultSet rs) throws SQLException {
        OcrRequestBean bean = new OcrRequestBean();
        bean.setId(rs.getLong("ID"));
        bean.setDocumentId(rs.getString("DOCUMENT_ID"));
        bean.setDocumentClass(rs.getString("DOCUMENT_CLASS"));
        long fileId = rs.getLong("FILE_ID");
        bean.setFileId(rs.wasNull() ? null : fileId);
        bean.setFileName(rs.getString("FILE_NAME"));
        bean.setRequestId(rs.getString("REQUEST_ID"));
        bean.setRequestFileNetId(rs.getString("REQUEST_FILENET_ID"));
        bean.setResponseFileNetId(rs.getString("RESPONSE_FILENET_ID"));
        int fileStatus = rs.getInt("FILE_STATUS");
        bean.setFileStatus(rs.wasNull() ? null : fileStatus);
        bean.setStatus(rs.getInt("STATUS"));
        bean.setErrorMessage(rs.getString("ERROR_MESSAGE"));
        bean.setNewDocumentId(rs.getString("NEW_DOCUMENT_ID"));
        return bean;
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
