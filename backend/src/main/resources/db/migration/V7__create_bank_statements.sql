-- One row per uploaded bank statement file.
CREATE TABLE bank_statement_imports (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL REFERENCES tenants (id),
    filename    VARCHAR(255) NOT NULL,
    file_hash   VARCHAR(64)  NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    row_count   INT          NOT NULL,
    imported_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_statement_imports_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_bank_statement_imports_file UNIQUE (tenant_id, file_hash),
    CONSTRAINT ck_bank_statement_imports_currency CHECK (currency ~ '^[A-Z]{3}$')
);

-- One row per transaction line. Positive amount = money in, negative = money out.
CREATE TABLE bank_transactions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    import_id    UUID         NOT NULL,
    posted_date  DATE         NOT NULL,
    description  VARCHAR(500) NOT NULL,
    reference    VARCHAR(100),
    amount_minor BIGINT       NOT NULL,
    currency     VARCHAR(3)   NOT NULL,
    CONSTRAINT fk_bank_transactions_import FOREIGN KEY (tenant_id, import_id)
        REFERENCES bank_statement_imports (tenant_id, id),
    CONSTRAINT ck_bank_transactions_amount CHECK (amount_minor <> 0),
    CONSTRAINT ck_bank_transactions_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_bank_transactions_import ON bank_transactions (tenant_id, import_id);
CREATE INDEX idx_bank_transactions_date ON bank_transactions (tenant_id, posted_date);
CREATE INDEX idx_bank_transactions_amount ON bank_transactions (tenant_id, amount_minor);