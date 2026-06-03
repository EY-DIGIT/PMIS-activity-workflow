-- =====================================================================
--  One-time migration: rename business_id -> activity_id, add project_id
--
--  Run this ONCE against your existing PostgreSQL database BEFORE
--  restarting the app. After this runs and Hibernate boots with
--  ddl-auto=update, the schema matches the new entities exactly.
--
--  If your tables are still empty, you can skip this script — Hibernate
--  will create the new schema directly on first boot.
-- =====================================================================

-- ---- aw_process_instance ----
ALTER TABLE aw_process_instance RENAME COLUMN business_id TO activity_id;
ALTER TABLE aw_process_instance ADD  COLUMN IF NOT EXISTS project_id VARCHAR(256);
DROP INDEX IF EXISTS idx_pi_business_id;
DROP INDEX IF EXISTS idx_pi_lookup;
CREATE INDEX IF NOT EXISTS idx_pi_activity_id ON aw_process_instance (activity_id);
CREATE INDEX IF NOT EXISTS idx_pi_project_id  ON aw_process_instance (project_id);
CREATE INDEX IF NOT EXISTS idx_pi_lookup
    ON aw_process_instance (business_service, activity_id, created_time);

-- ---- aw_workflow_audit ----
ALTER TABLE aw_workflow_audit RENAME COLUMN business_id TO activity_id;
ALTER TABLE aw_workflow_audit ADD  COLUMN IF NOT EXISTS project_id VARCHAR(256);
DROP INDEX IF EXISTS idx_audit_business_id;
CREATE INDEX IF NOT EXISTS idx_audit_activity_id ON aw_workflow_audit (activity_id);
CREATE INDEX IF NOT EXISTS idx_audit_project_id  ON aw_workflow_audit (project_id);

-- ---- aw_document ----
ALTER TABLE aw_document RENAME COLUMN business_id TO activity_id;
-- project_id column already exists on aw_document — no add needed
DROP INDEX IF EXISTS idx_doc_business_id;
CREATE INDEX IF NOT EXISTS idx_doc_activity_id ON aw_document (activity_id);

-- ---- aw_parallel_participant ----
ALTER TABLE aw_parallel_participant RENAME COLUMN business_id TO activity_id;
-- project_id column already exists on aw_parallel_participant — no add needed
DROP INDEX IF EXISTS idx_participant_lookup;
CREATE INDEX IF NOT EXISTS idx_participant_lookup
    ON aw_parallel_participant (business_service, activity_id, state_name);

-- Note: the UNIQUE constraint on (business_service, business_id, state_name,
-- approver_user_uuid) was auto-created by Hibernate. PostgreSQL renames the
-- underlying column inside the constraint automatically; no manual fix needed.
