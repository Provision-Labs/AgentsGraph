package io.provisionlabs.agentsgraph.config.jdbc;

import io.provisionlabs.agentsgraph.config.ConfigStore;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.json.GraphJsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-backed {@link ConfigStore}: an {@code agentsgraph_graph_config} table (id, name,
 * description, config TEXT, created_at, updated_at) holding one row per graph, its {@code config}
 * column storing the framework's own graph JSON (see {@link GraphJsonMapper}). Works against any
 * JDBC {@link DataSource} (Postgres in production, H2 in tests) since it only uses plain,
 * ANSI-portable SQL. See {@code examples/sql/docscan-schema.sql} for the reference schema and
 * {@code examples/graphs/ocr-accounting.json} for a sample document.
 *
 * <p>The {@code id} column is expected to match the {@code id} embedded in the JSON {@code config}
 * document; {@link #putGraph} maintains that invariant.
 */
public final class JdbcConfigStore implements ConfigStore {

    private final DataSource dataSource;

    /** Ensures the {@code agentsgraph_graph_config} schema exists - the store owns its own storage. */
    public JdbcConfigStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        createSchema(dataSource);
    }

    /** Creates the {@code agentsgraph_graph_config} table if it doesn't already exist. */
    public static void createSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS agentsgraph_graph_config (" +
                            "id VARCHAR(128) PRIMARY KEY, " +
                            "name TEXT NOT NULL, " +
                            "description TEXT, " +
                            "config TEXT NOT NULL, " +
                            "created_at BIGINT, " +
                            "updated_at BIGINT)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create agentsgraph_graph_config schema", e);
        }
    }

    @Override
    public void putGraph(GraphDefinition graph) {
        String configJson = GraphJsonMapper.toJson(graph);
        long now = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE agentsgraph_graph_config SET name = ?, config = ?, updated_at = ? WHERE id = ?")) {
                update.setString(1, graph.getId());
                update.setString(2, configJson);
                update.setLong(3, now);
                update.setString(4, graph.getId());
                updated = update.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO agentsgraph_graph_config (id, name, description, config, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, graph.getId());
                    insert.setString(2, graph.getId());
                    insert.setString(3, null);
                    insert.setString(4, configJson);
                    insert.setLong(5, now);
                    insert.setLong(6, now);
                    insert.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save graph '" + graph.getId() + "'", e);
        }
    }

    @Override
    public Optional<GraphDefinition> findGraph(String graphId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT config FROM agentsgraph_graph_config WHERE id = ?")) {
            select.setString(1, graphId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(GraphJsonMapper.fromJson(rs.getString("config")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load graph '" + graphId + "'", e);
        }
    }

    @Override
    public List<GraphDefinition> findAll() {
        List<GraphDefinition> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT config FROM agentsgraph_graph_config")) {
            while (rs.next()) {
                result.add(GraphJsonMapper.fromJson(rs.getString("config")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load graphs", e);
        }
        return result;
    }
}
