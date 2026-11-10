ALTER TABLE guide_note ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE note_attachment ADD COLUMN retired BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_character_player ON "character" (player_id);
CREATE INDEX idx_run_member_character ON run_member (character_id);
CREATE INDEX idx_app_user_player ON app_user (player_id);
CREATE INDEX idx_note_attachment_upload_quota ON note_attachment (uploaded_by, created_at) WHERE note_id IS NULL AND NOT retired;
