-- Seed data for AgentsGraphServerDbModeTest: the H2 database (PostgreSQL mode) the server boots
-- from in the 'db' profile. The agentsgraph_* tables already exist by the time this runs - the
-- JDBC stores provision their own schemas when the Spring context constructs them.
--
-- One processor row, loaded REFLECTIVELY by the engine from instance_class (the test-classpath
-- EchoTestProcessor), and one graph referencing it - so the test covers the whole DB-first
-- deployment path: SQL -> JDBC stores -> engine -> REST API.

DELETE FROM agentsgraph_processor WHERE id = 'echo';
INSERT INTO agentsgraph_processor (id, name, is_external, instance_class, params)
VALUES ('echo', 'Echo (test)', FALSE,
        'io.provisionlabs.agentsgraph.adminserver.EchoTestProcessor', '{}');

DELETE FROM agentsgraph_graph_config WHERE id = 'db-graph';
INSERT INTO agentsgraph_graph_config (id, name, description, config, created_at, updated_at)
VALUES ('db-graph', 'DB-seeded graph', 'seeded by sql/admin-server-db-test-data.sql',
        '{"id":"db-graph","version":"v1","entry_node_id":"n0","nodes":[{"id":"n0","routing_strategy":"rules","routing_table":{"default":"e0"}}],"edges":[{"id":"e0","steps":[{"id":"s0","processor_id":"echo"}]}]}',
        1, 1);
