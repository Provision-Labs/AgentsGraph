package io.provisionlabs.agentsgraph.config.jdbc;

import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the reference SQL fixtures under {@code src/test/resources/sql} (mirroring
 * {@code examples/sql/docscan-schema.sql} and {@code docscan-seed-data.sql}, but H2-portable) are
 * readable by {@link JdbcConfigStore}/{@link JdbcProcessorDefinitionStore} - i.e. that rows
 * inserted by raw SQL, not just through the Java API, round-trip correctly.
 */
class SqlFixtureConfigStoreTest {

    @Test
    void readsAGraphAndItsProcessorsSeededByRawSql() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:SqlFixtureConfigStoreTest_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");

        try (Connection connection = dataSource.getConnection()) {
            runScript(connection, "/sql/schema.sql");
            runScript(connection, "/sql/ocr-accounting-seed.sql");
        }

        JdbcConfigStore configStore = new JdbcConfigStore(dataSource);
        Optional<GraphDefinition> graph = configStore.findGraph("ocr-accounting");
        assertThat(graph).isPresent();
        assertThat(graph.get().getTemplates()).containsExactly("file:accountant", "file:default");
        assertThat(graph.get().getEdge("edge_ocr_pipeline").getSteps()).hasSize(2);

        JdbcProcessorDefinitionStore processorStore = new JdbcProcessorDefinitionStore(dataSource);
        List<ProcessorDefinition> processors = processorStore.findAll();
        assertThat(processors).extracting(ProcessorDefinition::getId)
                .containsExactlyInAnyOrder("docscan-ocr", "llm-postprocessor");
        assertThat(processors).allMatch(ProcessorDefinition::isExternal);
    }

    private static void runScript(Connection connection, String resourcePath) throws SQLException {
        String content = readResource(resourcePath);
        try (Statement statement = connection.createStatement()) {
            for (String sql : content.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    statement.execute(trimmed);
                }
            }
        }
    }

    private static String readResource(String resourcePath) {
        try (InputStream in = SqlFixtureConfigStoreTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
