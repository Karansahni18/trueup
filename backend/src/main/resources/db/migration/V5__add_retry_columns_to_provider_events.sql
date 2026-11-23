-- When is this event next due to be worked on? (also used as the "deadline" while someone is working on it)
ALTER TABLE provider_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Add the new status PROCESSING.
ALTER TABLE provider_events DROP CONSTRAINT ck_provider_events_status;
ALTER TABLE provider_events
    ADD CONSTRAINT ck_provider_events_status
    CHECK (status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'DEAD'));

-- A small index that only covers events still waiting for work.
DROP INDEX idx_provider_events_status;
CREATE INDEX idx_provider_events_due ON provider_events (next_attempt_at)
    WHERE status IN ('RECEIVED', 'FAILED', 'PROCESSING');