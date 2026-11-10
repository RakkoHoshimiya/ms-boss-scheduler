ALTER TABLE note_attachment ALTER COLUMN note_id DROP NOT NULL;
ALTER TABLE note_attachment ADD COLUMN uploaded_by BIGINT;
ALTER TABLE note_attachment ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_note_attachment_staged ON note_attachment(created_at) WHERE note_id IS NULL;
