package io.provisionlabs.agentsgraph;

import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcConfigStore;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;
import io.provisionlabs.agentsgraph.engine.ProcessorLoader;
import io.provisionlabs.agentsgraph.trace.jdbc.JdbcTraceStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors the legacy docscan-pipeline's {@code PipelineConfigServiceTest}: the graph config (by
 * id) and the processor list are seeded into the database by a plain SQL script (see
 * {@code sql/graph-config-service-test-data.sql}) and loaded from there - covering lazy first-use
 * loading, {@link GraphConfigService#reload()} picking up changed processor rows, graph
 * hot-updates taking effect without any reload, and programmatic processors surviving reloads.
 */
class GraphConfigServiceTest {

    private DataSource dataSource;
    private AgentsGraphEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:GraphConfigServiceTest_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource = h2;
        // The engine never sees the DataSource - each JDBC store owns its own schema/storage.
        engine = new AgentsGraphEngine(new JdbcConfigStore(dataSource),
                new JdbcProcessorDefinitionStore(dataSource), new JdbcTraceStore(dataSource));
        runScript("/sql/graph-config-service-test-data.sql");
    }

    @Test
    void loadsGraphAndProcessorsSeededByRawSql() {
        GraphConfigService service = new GraphConfigService(engine);

        assertThat(service.getAllGraphs()).hasSize(1);
        assertThat(service.getGraph("db-loaded-graph").getVersion()).isEqualTo("v1");
        assertThat(service.dumpGraphJson("db-loaded-graph")).contains("\"id\":\"db-loaded-graph\"");

        ExecutionContext result = service.execute("db-loaded-graph",
                ExecutionContext.newFlow(Map.of("q", "hello"), Map.of()));

        assertThat(result.getAccumulatedState())
                .containsEntry("echoed", "v1:hello")
                .containsEntry("pinged", true);
    }

    @Test
    void processorsLoadedFromTheStoreReportHealth() {
        GraphConfigService service = new GraphConfigService(engine);
        ProcessorLoader.LoadResult loadResult = service.reload();

        assertThat(loadResult.getFailures()).isEmpty();
        assertThat(engine.getProcessorHealthMonitor().isHealthy("seeded-echo")).isTrue();
        assertThat(engine.getProcessorHealthMonitor().isHealthy("seeded-external")).isTrue();
    }

    @Test
    void reloadPicksUpAChangedProcessorRow() throws Exception {
        GraphConfigService service = new GraphConfigService(engine);
        ExecutionContext before = service.execute("db-loaded-graph",
                ExecutionContext.newFlow(Map.of("q", "x"), Map.of()));
        assertThat(before.getAccumulatedState()).containsEntry("echoed", "v1:x");

        execute("UPDATE agentsgraph_processor SET params = '{\"prefix\": \"v2\"}' WHERE id = 'seeded-echo'");

        // Not yet reloaded - the registered processor instance still carries the old params.
        ExecutionContext stale = service.execute("db-loaded-graph",
                ExecutionContext.newFlow(Map.of("q", "x"), Map.of()));
        assertThat(stale.getAccumulatedState()).containsEntry("echoed", "v1:x");

        service.reload();

        ExecutionContext after = service.execute("db-loaded-graph",
                ExecutionContext.newFlow(Map.of("q", "x"), Map.of()));
        assertThat(after.getAccumulatedState()).containsEntry("echoed", "v2:x");
    }

    @Test
    void graphUpdatesInTheDatabaseTakeEffectWithoutAnyReload() throws Exception {
        GraphConfigService service = new GraphConfigService(engine);
        assertThat(service.getGraph("db-loaded-graph").getVersion()).isEqualTo("v1");

        // Drop the second step and bump the version, straight in the DB - as an operator would.
        execute("UPDATE agentsgraph_graph_config SET config = '{"
                + "\"id\": \"db-loaded-graph\", \"version\": \"v2\", \"entry_node_id\": \"n0\","
                + "\"nodes\": [{\"id\": \"n0\", \"routing_strategy\": \"rules\", \"routing_table\": {\"default\": \"e0\"}}],"
                + "\"edges\": [{\"id\": \"e0\", \"steps\": [{\"id\": \"s0\", \"processor_id\": \"seeded-echo\", \"output_to_save\": [\"echoed\"]}]}]"
                + "}' WHERE id = 'db-loaded-graph'");

        ExecutionContext result = service.execute("db-loaded-graph",
                ExecutionContext.newFlow(Map.of("q", "y"), Map.of()));

        assertThat(service.getGraph("db-loaded-graph").getVersion()).isEqualTo("v2");
        assertThat(result.getAccumulatedState())
                .containsEntry("echoed", "v1:y")
                .doesNotContainKey("pinged");
    }

    @Test
    void programmaticProcessorsOverrideDbRowsAndSurviveReload() {
        Processor stub = (context, step) -> Map.of("echoed", "programmatic", "pinged", true);
        GraphConfigService service = new GraphConfigService(engine, Map.of("seeded-echo", stub));

        ExecutionContext first = service.execute("db-loaded-graph",
                ExecutionContext.newFlow(Map.of("q", "z"), Map.of()));
        assertThat(first.getAccumulatedState()).containsEntry("echoed", "programmatic");

        service.reload();

        ExecutionContext second = service.execute("db-loaded-graph",
                ExecutionContext.newFlow(Map.of("q", "z"), Map.of()));
        assertThat(second.getAccumulatedState()).containsEntry("echoed", "programmatic");
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void runScript(String resource) throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing test resource: " + resource);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String part : sql.split(";")) {
                // Strip full-line comments so a part that is only comments doesn't execute as empty.
                String trimmed = part.lines()
                        .filter(line -> !line.trim().startsWith("--"))
                        .reduce((a, b) -> a + "\n" + b).orElse("").trim();
                if (!trimmed.isEmpty()) {
                    statement.execute(trimmed);
                }
            }
        }
    }

    /** Reflectively loaded from the SQL fixture; init(params) drives its output, proving reload re-inits. */
    public static class SeededEchoProcessor implements Processor {
        private String prefix;

        @Override
        public void init(Map<String, Object> params) {
            this.prefix = String.valueOf(params.getOrDefault("prefix", "?"));
        }

        @Override
        public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
            return Map.of("echoed", prefix + ":" + context.getInputData().get("q"));
        }
    }

    /** Reflectively loaded from the SQL fixture; flagged is_external, so health is monitored. */
    public static class SeededExternalProcessor implements Processor {
        @Override
        public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
            return Map.of("pinged", true);
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    }
}
