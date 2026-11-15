ALTER TABLE guide_note ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_guide_note_deleted ON guide_note (deleted_at) WHERE deleted_at IS NOT NULL;

CREATE TABLE note_draft (
 id VARCHAR(36) PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 note_id BIGINT,
 source_version BIGINT,
 version BIGINT NOT NULL DEFAULT 0,
 title VARCHAR(120) NOT NULL DEFAULT '',
 content TEXT NOT NULL DEFAULT '',
 pinned BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 deleted_at TIMESTAMP WITH TIME ZONE,
 completed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_note_draft_owner ON note_draft (owner_id, updated_at DESC);
CREATE INDEX idx_note_draft_deleted ON note_draft (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE TABLE note_draft_attachment (
 draft_id VARCHAR(36) NOT NULL REFERENCES note_draft(id) ON DELETE CASCADE,
 attachment_id VARCHAR(255) NOT NULL REFERENCES note_attachment(id),
 PRIMARY KEY (draft_id, attachment_id)
);
CREATE INDEX idx_note_draft_attachment_file ON note_draft_attachment (attachment_id);
