-- =====================================================================
--  V3: division support on the parallel gate
--
--  - aw_parallel_participant gains division_code + division_name
--  - new aw_division_user table for collaborator users (record-only)
--
--  Run once, BEFORE restarting the app. Hibernate's ddl-auto=update will
--  then validate (or top up the new index) on next boot.
-- =====================================================================

-- ---- 1. add division columns to existing participant rows ----
ALTER TABLE aw_parallel_participant
    ADD COLUMN IF NOT EXISTS division_code VARCHAR(128);
ALTER TABLE aw_parallel_participant
    ADD COLUMN IF NOT EXISTS division_name VARCHAR(256);

CREATE INDEX IF NOT EXISTS idx_participant_division
    ON aw_parallel_participant (division_code);

-- ---- 2. new collaborator-users table ----
CREATE TABLE IF NOT EXISTS aw_division_user (
    uuid             VARCHAR(64)  PRIMARY KEY,
    business_service VARCHAR(256) NOT NULL,
    activity_id      VARCHAR(256) NOT NULL,
    project_id       VARCHAR(256),
    state_name       VARCHAR(256) NOT NULL,
    division_code    VARCHAR(128) NOT NULL,
    division_name    VARCHAR(256),
    user_uuid        VARCHAR(64)  NOT NULL,
    user_email       VARCHAR(256),
    user_name        VARCHAR(256),
    created_at       BIGINT       NOT NULL,
    CONSTRAINT uk_div_user_record_state_user UNIQUE
        (business_service, activity_id, state_name, division_code, user_uuid)
);

CREATE INDEX IF NOT EXISTS idx_div_user_lookup
    ON aw_division_user (business_service, activity_id, state_name);
CREATE INDEX IF NOT EXISTS idx_div_user_division ON aw_division_user (division_code);
CREATE INDEX IF NOT EXISTS idx_div_user_user     ON aw_division_user (user_uuid);
