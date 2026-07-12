-- Fixture for AgentsGraphTestHarnessTest: a two-edge graph whose single "external" step
-- (ext-service) is expected to be replaced by a MockProcessor in tests. Note the semicolon in
-- this comment; SqlScriptRunner must not split on it.

INSERT INTO agentsgraph_processor (id, name, is_external, instance_class, params) VALUES
    ('ext-service', 'External service call', true, 'no.such.clazz.LoadedOnlyWhenNotMocked', '{}');

INSERT INTO agentsgraph_graph_config (id, name, description, config, created_at, updated_at) VALUES
    ('harness-graph', 'Harness test graph', 'Two edges + fallback', '{
  "id": "harness-graph",
  "version": "v1",
  "entry_node_id": "n0",
  "nodes": [
    {
      "id": "n0",
      "routing_strategy": "rules",
      "routing_table": {"mode==ext": "e_ext", "default": "e_plain"},
      "fallback_edge_id": "e_fallback"
    }
  ],
  "edges": [
    {"id": "e_ext", "steps": [
      {"id": "s0", "processor_id": "ext-service", "output_to_save": ["result"]}
    ], "tags_to_add": ["external_called"]},
    {"id": "e_plain", "steps": [
      {"id": "s0", "processor_id": "ext-service", "output_to_save": ["result"]}
    ], "tags_to_add": ["plain"]},
    {"id": "e_fallback", "steps": [
      {"id": "s0", "processor_id": "fallback", "output_to_save": ["answer"]}
    ], "tags_to_add": ["needs_review"]}
  ]
}', 1752300000000, 1752300000000);
