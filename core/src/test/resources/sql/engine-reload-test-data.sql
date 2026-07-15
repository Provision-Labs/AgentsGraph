-- Test fixture for AgentsGraphEngineReloadTest, mirroring the legacy docscan-pipeline's
-- pipeline-docscan-ocr-test-data.sql convention: the graph config (by id) AND the processor list
-- are plain SQL rows, loaded from the database - nothing is seeded from bundled resources.
-- Schema (agentsgraph_graph_config/agentsgraph_processor) is created by AgentsGraphEngine.jdbc()
-- before this script runs.

INSERT INTO agentsgraph_processor (id, name, is_external, instance_class, params) VALUES
    ('seeded-echo', 'Echo processor', false,
     'io.provisionlabs.agentsgraph.AgentsGraphEngineReloadTest$SeededEchoProcessor',
     '{"prefix": "v1"}'),
    ('seeded-external', 'External-service processor', true,
     'io.provisionlabs.agentsgraph.AgentsGraphEngineReloadTest$SeededExternalProcessor',
     '{}');

INSERT INTO agentsgraph_graph_config (id, name, description, config, created_at, updated_at) VALUES
    ('db-loaded-graph', 'DB-loaded graph', 'Graph loaded entirely from SQL rows', '{
  "id": "db-loaded-graph",
  "version": "v1",
  "entry_node_id": "n0",
  "nodes": [
    {"id": "n0", "routing_strategy": "rules", "routing_table": {"default": "e0"}}
  ],
  "edges": [
    {"id": "e0", "steps": [
      {"id": "s0", "processor_id": "seeded-echo", "output_to_save": ["echoed"]},
      {"id": "s1", "processor_id": "seeded-external", "output_to_save": ["pinged"]}
    ]}
  ]
}', 1752300000000, 1752300000000);
