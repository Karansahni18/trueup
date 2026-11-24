-- Each ledger entry can remember which provider message created it (like a receipt number).
ALTER TABLE journal_entries
    ADD COLUMN source_event_id UUID REFERENCES provider_events (id);

-- One message can create at most one ledger entry.
CREATE UNIQUE INDEX uq_journal_entries_source_event
    ON journal_entries (source_event_id)
    WHERE source_event_id IS NOT NULL;