-- Run this manually against NCGR_MAHFOUZAT before deploying the new Idrak-integrated batch.
-- Tracks each document's round-trip through the Idrak OCR integration (upload -> SOAP request ->
-- async MQ completion -> fetch/decrypt result), replacing the old in-process-only Tesseract flow
-- that used to go straight from DMS_FILES.OCR_STATUS to a finished document with no intermediate state.

CREATE TABLE OCR_REQUEST (
    ID                    BIGINT IDENTITY(1,1) PRIMARY KEY,
    DOCUMENT_ID           NVARCHAR(50)  NOT NULL,   -- our own FileNet document id (DMS_FILES.DOCUMENT_ID at the time we picked it up)
    DOCUMENT_CLASS        NVARCHAR(100) NULL,
    FILE_ID               BIGINT        NULL,
    FILE_NAME             NVARCHAR(255) NULL,

    REQUEST_ID            NVARCHAR(36)  NOT NULL,   -- our generated GUID, sent to Idrak as RequestId / used in RqUID
    REQUEST_FILENET_ID    NVARCHAR(100) NULL,       -- CMIS/FileNet object id of the ENCRYPTED file we uploaded to the client's OCRIdark folder
    RESPONSE_FILENET_ID   NVARCHAR(100) NULL,       -- CMIS/FileNet object id of the encrypted OCR result (from the MQ notify)
    FILE_STATUS           INT           NULL,       -- Idrak's own FileStatus code from the MQ notify (1 = success, see Appendix)

    STATUS                INT           NOT NULL DEFAULT 0, -- see OcrRequestBean.STATUS_* constants
    ERROR_MESSAGE         NVARCHAR(1024) NULL,
    NEW_DOCUMENT_ID       NVARCHAR(50)  NULL,       -- our new FileNet document id after saving the decrypted OCR result as a new version

    CREATED_DATE          DATETIME      NOT NULL DEFAULT GETDATE(),
    UPDATED_DATE          DATETIME      NULL
);

CREATE UNIQUE INDEX IX_OCR_REQUEST_REQUEST_ID ON OCR_REQUEST (REQUEST_ID);
CREATE INDEX IX_OCR_REQUEST_STATUS ON OCR_REQUEST (STATUS);
CREATE INDEX IX_OCR_REQUEST_DOCUMENT_ID ON OCR_REQUEST (DOCUMENT_ID);
