CREATE TABLE accounts (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID         NOT NULL REFERENCES tenants (id),
    code       VARCHAR(30)  NOT NULL,
    name       VARCHAR(200) NOT NULL,
    type       VARCHAR(20)  NOT NULL,
    currency   VARCHAR(3)   NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounts_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_accounts_tenant_id_currency UNIQUE (tenant_id, id, currency),
    CONSTRAINT ck_accounts_type CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT ck_accounts_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE journal_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL REFERENCES tenants (id),
    entry_date  DATE         NOT NULL,
    description VARCHAR(500) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_journal_entries_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_journal_entries_tenant_date ON journal_entries (tenant_id, entry_date);

CREATE TABLE journal_lines (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID       NOT NULL,
    entry_id     UUID       NOT NULL,
    account_id   UUID       NOT NULL,
    direction    VARCHAR(6) NOT NULL,
    amount_minor BIGINT     NOT NULL,
    currency     VARCHAR(3) NOT NULL,
    CONSTRAINT ck_journal_lines_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_journal_lines_amount CHECK (amount_minor > 0),
    CONSTRAINT fk_journal_lines_entry FOREIGN KEY (tenant_id, entry_id)
        REFERENCES journal_entries (tenant_id, id),
    CONSTRAINT fk_journal_lines_account FOREIGN KEY (tenant_id, account_id, currency)
        REFERENCES accounts (tenant_id, id, currency)
);

CREATE INDEX idx_journal_lines_entry ON journal_lines (tenant_id, entry_id);
CREATE INDEX idx_journal_lines_account ON journal_lines (tenant_id, account_id);

-- Every entry must balance (debits = credits) for each currency, checked at commit time.
CREATE FUNCTION check_journal_entry_balanced() RETURNS trigger AS $$
DECLARE
    v_unbalanced INT;
BEGIN
    SELECT count(*) INTO v_unbalanced FROM (
        SELECT currency
        FROM journal_lines
        WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id
        GROUP BY currency
        HAVING sum(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0
    ) AS unbalanced;

    IF v_unbalanced > 0 THEN
        RAISE EXCEPTION 'Journal entry % is not balanced', NEW.entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_entry_balanced
    AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balanced();

-- Every entry must have at least two lines, checked at commit time.
CREATE FUNCTION check_journal_entry_has_lines() RETURNS trigger AS $$
BEGIN
    IF (SELECT count(*) FROM journal_lines
        WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.id) < 2 THEN
        RAISE EXCEPTION 'Journal entry % must have at least two lines', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_entry_has_lines
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_has_lines();

-- The ledger is append-only: fix mistakes by posting a reversing entry.
CREATE FUNCTION prevent_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger rows are immutable: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();

CREATE TRIGGER trg_journal_lines_immutable
    BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();