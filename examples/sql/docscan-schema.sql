-- Reference schema for a Postgres-backed AgentsGraph deployment (see JdbcConfigStore,
-- JdbcProcessorDefinitionStore, JdbcTraceStore in the config/trace modules). AgentsGraph reuses
-- the same table shape the legacy docscan-pipeline used (plb_pipeline_config /
-- plb_pipeline_processor); only the JSON stored in the "config" column is new (the framework's
-- own graph format, see examples/graphs/ocr-accounting.json), not the tables themselves.

CREATE TABLE IF NOT EXISTS plb_pipeline_config (
    id          VARCHAR(128) PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT,
    config      TEXT NOT NULL,   -- a full graph document, see examples/graphs/*.json
    created_at  BIGINT,
    updated_at  BIGINT
);

CREATE TABLE IF NOT EXISTS plb_pipeline_processor (
    id             VARCHAR(128) PRIMARY KEY,
    name           VARCHAR(128),
    is_external    BOOLEAN,
    instance_class VARCHAR(256) NOT NULL,  -- fully-qualified class implementing engine.Processor
    params         TEXT                     -- JSON object, e.g. {"processUrl": "http://..."}
);

-- Status & Trace Store (see JdbcTraceStore). Persists status/tags/telemetry durably; the
-- per-node context-snapshot audit log is intentionally kept in-process only (see its Javadoc).
CREATE TABLE IF NOT EXISTS plb_execution_trace (
    flow_id        VARCHAR(128) PRIMARY KEY,
    tenant_id      VARCHAR(128),
    status         VARCHAR(32) NOT NULL,
    tags           TEXT,
    step_count     INT DEFAULT 0,
    token_cost     BIGINT DEFAULT 0,
    duration_ms    BIGINT DEFAULT 0,
    retry_attempts INT DEFAULT 0
);
