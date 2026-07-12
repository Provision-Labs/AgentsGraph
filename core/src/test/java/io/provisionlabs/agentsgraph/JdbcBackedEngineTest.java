package io.provisionlabs.agentsgraph;

import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcConfigStore;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.config.json.GraphJsonMapper;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.jdbc.JdbcTraceStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the store-driven engine shape: the engine itself never touches a {@code DataSource} -
 * a consumer wires JDBC-backed {@code ConfigStore}/{@code ProcessorDefinitionStore}/{@code
 * TraceStore} implementations (each of which ensures its own schema on construction) and hands
 * them to {@link AgentsGraphEngine}'s three-store constructor. Swapping to JSON- or
 * in-memory-backed stores is a wiring change only.
 */
class JdbcBackedEngineTest {

    private static final String GRAPH_JSON = "{\n" +
            "  \"id\": \"jdbc-factory-graph\",\n" +
            "  \"version\": \"v1\",\n" +
            "  \"entry_node_id\": \"n0\",\n" +
            "  \"nodes\": [\n" +
            "    {\"id\": \"n0\", \"routing_strategy\": \"rules\", \"routing_table\": {\"default\": \"e0\"}}\n" +
            "  ],\n" +
            "  \"edges\": [\n" +
            "    {\"id\": \"e0\", \"steps\": [\n" +
            "      {\"id\": \"s0\", \"processor_id\": \"echo\", \"output_to_save\": [\"echoed\"]}\n" +
            "    ]}\n" +
            "  ]\n" +
            "}";

    private static DataSource freshH2DataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    /** What a Spring context does: three store beans (each owning its schema) -> one engine bean. */
    private static AgentsGraphEngine newJdbcEngine(DataSource dataSource) {
        return new AgentsGraphEngine(new JdbcConfigStore(dataSource),
                new JdbcProcessorDefinitionStore(dataSource), new JdbcTraceStore(dataSource));
    }

    @Test
    void jdbcBackedStoresCreateTheirSchemaAndTheEngineRunsOnThem() {
        DataSource dataSource = freshH2DataSource();

        AgentsGraphEngine engine = newJdbcEngine(dataSource);

        engine.deployGraphIfAbsent(GraphJsonMapper.fromJson(GRAPH_JSON));
        engine.seedAndLoadProcessors(List.of(
                new ProcessorDefinition("echo", "Echo", false, EchoProcessor.class.getName(), Map.of())));

        Map<String, Object> input = new HashMap<>();
        input.put("hasFile", false);
        ExecutionContext result = engine.execute("jdbc-factory-graph", ExecutionContext.newFlow(input, Map.of()));

        assertThat(result.getAccumulatedState()).containsEntry("echoed", "ok");
        assertThat(engine.getConfigStore().findGraph("jdbc-factory-graph")).isPresent();
        assertThat(engine.getProcessorDefinitionStore().find("echo")).isPresent();
    }

    @Test
    void deployGraphIfAbsentDoesNotOverwriteAnExistingGraph() {
        DataSource dataSource = freshH2DataSource();
        AgentsGraphEngine engine = newJdbcEngine(dataSource);

        GraphDefinition original = GraphJsonMapper.fromJson(GRAPH_JSON);
        engine.deployGraphIfAbsent(original);
        engine.deployGraphIfAbsent(GraphJsonMapper.fromJson(GRAPH_JSON.replace("jdbc-factory-graph", "jdbc-factory-graph")
                .replace("\"v1\"", "\"v2-should-not-apply\"")));

        assertThat(engine.getConfigStore().findGraph("jdbc-factory-graph").orElseThrow().getVersion())
                .isEqualTo("v1");
    }

    @Test
    void seedAndLoadProcessorsOnlySeedsWhenStoreIsEmpty() {
        DataSource dataSource = freshH2DataSource();
        AgentsGraphEngine engine = newJdbcEngine(dataSource);

        engine.getProcessorDefinitionStore().put(
                new ProcessorDefinition("echo", "Custom", false, EchoProcessor.class.getName(), Map.of("custom", true)));

        engine.seedAndLoadProcessors(List.of(
                new ProcessorDefinition("echo", "Default", false, EchoProcessor.class.getName(), Map.of())));

        assertThat(engine.getProcessorDefinitionStore().find("echo").orElseThrow().getParams())
                .containsEntry("custom", true);
    }

    public static class EchoProcessor implements io.provisionlabs.agentsgraph.engine.Processor {
        @Override
        public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
            return Map.of("echoed", "ok");
        }
    }
}
