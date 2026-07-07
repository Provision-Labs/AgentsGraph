-- H2-compatible test schema, mirroring examples/sql/docscan-schema.sql (same table/column shape
-- JdbcConfigStore.createSchema()/JdbcProcessorDefinitionStore.createSchema() produce).

CREATE TABLE IF NOT EXISTS agentsgraph_graph_config (
    id          VARCHAR(128) PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT,
    config      TEXT NOT NULL,
    created_at  BIGINT,
    updated_at  BIGINT
);

CREATE TABLE IF NOT EXISTS agentsgraph_processor (
    id             VARCHAR(128) PRIMARY KEY,
    name           VARCHAR(128),
    is_external    BOOLEAN,
    instance_class VARCHAR(256) NOT NULL,
    params         TEXT
);
