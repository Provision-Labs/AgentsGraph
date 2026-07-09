package io.provisionlabs.agentsgraph.config.jdbc;

import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import io.provisionlabs.agentsgraph.config.ProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.config.json.ProcessorJsonMapper;

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
 * Database-backed {@link ProcessorDefinitionStore}: an {@code agentsgraph_processor} table
 * (id, name, is_external, instance_class, params TEXT) holding one row per registrable processor.
 */
public final class JdbcProcessorDefinitionStore implements ProcessorDefinitionStore {

    private final DataSource dataSource;

    public JdbcProcessorDefinitionStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Creates the {@code agentsgraph_processor} table if it doesn't already exist. */
    public static void createSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS agentsgraph_processor (" +
                            "id VARCHAR(128) PRIMARY KEY, " +
                            "name VARCHAR(128), " +
                            "is_external BOOLEAN, " +
                            "instance_class VARCHAR(256) NOT NULL, " +
                            "params TEXT)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create agentsgraph_processor schema", e);
        }
    }

    @Override
    public void put(ProcessorDefinition definition) {
        String paramsJson = ProcessorJsonMapper.toParamsJson(definition.getParams());
        try (Connection connection = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE agentsgraph_processor SET name = ?, is_external = ?, instance_class = ?, params = ? " +
                            "WHERE id = ?")) {
                update.setString(1, definition.getName());
                update.setBoolean(2, definition.isExternal());
                update.setString(3, definition.getInstanceClass());
                update.setString(4, paramsJson);
                update.setString(5, definition.getId());
                updated = update.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO agentsgraph_processor (id, name, is_external, instance_class, params) " +
                                "VALUES (?, ?, ?, ?, ?)")) {
                    insert.setString(1, definition.getId());
                    insert.setString(2, definition.getName());
                    insert.setBoolean(3, definition.isExternal());
                    insert.setString(4, definition.getInstanceClass());
                    insert.setString(5, paramsJson);
                    insert.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save processor definition '" + definition.getId() + "'", e);
        }
    }

    @Override
    public Optional<ProcessorDefinition> find(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT id, name, is_external, instance_class, params FROM agentsgraph_processor WHERE id = ?")) {
            select.setString(1, id);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(toDefinition(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load processor definition '" + id + "'", e);
        }
    }

    @Override
    public List<ProcessorDefinition> findAll() {
        List<ProcessorDefinition> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT id, name, is_external, instance_class, params FROM agentsgraph_processor")) {
            while (rs.next()) {
                result.add(toDefinition(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load processor definitions", e);
        }
        return result;
    }

    private ProcessorDefinition toDefinition(ResultSet rs) throws SQLException {
        return new ProcessorDefinition(
                rs.getString("id"),
                rs.getString("name"),
                rs.getBoolean("is_external"),
                rs.getString("instance_class"),
                ProcessorJsonMapper.fromParamsJson(rs.getString("params")));
    }
}
