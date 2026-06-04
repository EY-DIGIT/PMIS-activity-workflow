-- =====================================================================
--  V4: slim down aw_document
--
--  We no longer track file metadata locally — only the upstream comment
--  id is needed, since the upstream API owns file name / type / URL.
--  Adds uploaded_by_email so we have it in one place per requirement.
-- =====================================================================

ALTER TABLE aw_document DROP COLUMN IF EXISTS store_id;
ALTER TABLE aw_document DROP COLUMN IF EXISTS file_url;
ALTER TABLE aw_document DROP COLUMN IF EXISTS file_name;
ALTER TABLE aw_document DROP COLUMN IF EXISTS content_type;
ALTER TABLE aw_document DROP COLUMN IF EXISTS file_size;

ALTER TABLE aw_document
    ADD COLUMN IF NOT EXISTS uploaded_by_email VARCHAR(256);

-- Drop the duplicate index that crept in (idx_doc_activity_id appeared twice)
-- and re-create the clean set Hibernate now expects.
DROP INDEX IF EXISTS idx_doc_business_id;          -- legacy from before the rename
CREATE INDEX IF NOT EXISTS idx_doc_activity_id  ON aw_document (activity_id);
CREATE INDEX IF NOT EXISTS idx_doc_project_id   ON aw_document (project_id);
CREATE INDEX IF NOT EXISTS idx_doc_uploaded_by  ON aw_document (uploaded_by_uuid);
CREATE INDEX IF NOT EXISTS idx_doc_created_at   ON aw_document (created_at);
