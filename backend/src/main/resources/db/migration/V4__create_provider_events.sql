-- One row per tenant per payment provider: the URL key and the signing secret.
-- NOTE: the secret is stored as plain text for now. Encrypting it at rest is a later hardening step.
CREATE TABLE provider_connections (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL REFERENCES tenants (id),
    provider       VARCHAR(20)  NOT NULL,
    webhook_key    VARCHAR(64)  NOT NULL,
    signing_secret VARCHAR(200) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_connections_tenant_provider UNIQUE (tenant_id, provider),
    CONSTRAINT uq_provider_connections_webhook_key UNIQUE (webhook_key),
    CONSTRAINT ck_provider_connections_provider CHECK (provider IN ('STRIPE'))
);

-- Every event we receive, stored exactly once, with its raw payload.
CREATE TABLE provider_events (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants (id),
    provider          VARCHAR(20)  NOT NULL,
    external_event_id VARCHAR(100) NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    payload           JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    attempts          INT          NOT NULL DEFAULT 0,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ,
    last_error        VARCHAR(1000),
    CONSTRAINT uq_provider_events_idempotency UNIQUE (tenant_id, provider, external_event_id),
    CONSTRAINT ck_provider_events_provider CHECK (provider IN ('STRIPE')),
    CONSTRAINT ck_provider_events_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'DEAD'))
);

CREATE INDEX idx_provider_events_status ON provider_events (status, received_at);