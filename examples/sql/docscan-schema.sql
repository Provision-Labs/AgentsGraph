-- Reference schema for a Postgres-backed AgentsGraph deployment (see JdbcConfigStore,
-- JdbcProcessorDefinitionStore, JdbcTraceStore in the config/trace modules). The "config" column
-- of agentsgraph_graph_config holds the framework's own graph JSON document, see
-- examples/graphs/ocr-accounting.json for a full example.

CREATE TABLE IF NOT EXISTS agentsgraph_graph_config (
    id          VARCHAR(128) PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT,
    config      TEXT NOT NULL,   -- a full graph document, see examples/graphs/*.json
    created_at  BIGINT,
    updated_at  BIGINT
);

CREATE TABLE IF NOT EXISTS agentsgraph_processor (
    id             VARCHAR(128) PRIMARY KEY,
    name           VARCHAR(128),
    is_external    BOOLEAN,
    instance_class VARCHAR(256) NOT NULL,  -- fully-qualified class implementing engine.Processor
    params         TEXT                     -- JSON object, e.g. {"processUrl": "http://..."}
);

-- Status & Trace Store (see JdbcTraceStore). Persists status/tags/telemetry durably; the
-- per-node context-snapshot audit log is intentionally kept in-process only (see its Javadoc).
CREATE TABLE IF NOT EXISTS agentsgraph_execution_trace (
    flow_id        VARCHAR(128) PRIMARY KEY,
    tenant_id      VARCHAR(128),
    status         VARCHAR(32) NOT NULL,
    tags           TEXT,
    error          TEXT,
    step_count     INT DEFAULT 0,
    token_cost     BIGINT DEFAULT 0,
    duration_ms    BIGINT DEFAULT 0,
    retry_attempts INT DEFAULT 0
);

-- Step-level DEBUG trace (see JdbcTraceStore): one row per executed step when a flow runs in
-- debug mode - full input-context snapshot + raw output, enabling post-mortem inspection and
-- AgentsGraphEngine.resumeFrom(flowId, seq). Not written at all for normal (non-debug) runs.
CREATE TABLE IF NOT EXISTS agentsgraph_step_trace (
    flow_id        VARCHAR(128) NOT NULL,
    seq            BIGINT NOT NULL,       -- monotonic per-flow step counter (execution order)
    graph_id       VARCHAR(128),
    graph_version  VARCHAR(64),
    node_id        VARCHAR(128),
    edge_id        VARCHAR(128),
    step_id        VARCHAR(128),
    step_index     INT,                   -- position within the edge's pipeline (resume entry)
    processor_ref  VARCHAR(128),
    input_context  TEXT,                  -- full context snapshot the step saw (ContextJsonCodec)
    output_raw     TEXT,                  -- raw output before output_to_next/output_to_save
    status         VARCHAR(32) NOT NULL,  -- COMPLETED | FAILED (same enum as the flow status)
    error          TEXT,                  -- stack trace when FAILED
    restartable    BOOLEAN,               -- false if a value was dropped/truncated in the snapshot
    started_at     BIGINT,
    duration_ms    BIGINT,
    PRIMARY KEY (flow_id, seq)
);
