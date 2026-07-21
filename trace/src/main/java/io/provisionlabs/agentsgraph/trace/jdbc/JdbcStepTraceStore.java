package io.provisionlabs.agentsgraph.trace.jdbc;

import io.provisionlabs.agentsgraph.trace.StepStatus;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import io.provisionlabs.agentsgraph.trace.StepTraceStore;

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
 * Database-backed {@link StepTraceStore}: one row per executed step in {@code
 * agentsgraph_step_trace}, keyed by {@code (flow_id, seq)}. Unlike {@link JdbcTraceStore}'s
 * in-process audit-log cache, step traces ARE fully persisted - the whole point is picking a
 * failed flow apart (and resuming it) after the fact, possibly from another process.
 */
public final class JdbcStepTraceStore implements StepTraceStore {

    private final DataSource dataSource;

    /** Ensures the {@code agentsgraph_step_trace} schema exists - the store owns its own storage. */
    public JdbcStepTraceStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        createSchema(dataSource);
    }

    /** Creates the {@code agentsgraph_step_trace} table if it doesn't already exist. */
    public static void createSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS agentsgraph_step_trace (" +
                            "flow_id VARCHAR(128) NOT NULL, " +
                            "seq BIGINT NOT NULL, " +
                            "graph_id VARCHAR(128), " +
                            "graph_version VARCHAR(64), " +
                            "node_id VARCHAR(128), " +
                            "edge_id VARCHAR(128), " +
                            "step_id VARCHAR(128), " +
                            "step_index INT, " +
                            "processor_ref VARCHAR(128), " +
                            "input_context TEXT, " +
                            "output_raw TEXT, " +
                            "status VARCHAR(16) NOT NULL, " +
                            "error TEXT, " +
                            "restartable BOOLEAN, " +
                            "started_at BIGINT, " +
                            "duration_ms BIGINT, " +
                            "PRIMARY KEY (flow_id, seq))");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create agentsgraph_step_trace schema", e);
        }
    }

    @Override
    public void append(StepTraceRecord record) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO agentsgraph_step_trace (flow_id, seq, graph_id, graph_version, " +
                             "node_id, edge_id, step_id, step_index, processor_ref, input_context, " +
                             "output_raw, status, error, restartable, started_at, duration_ms) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, record.getFlowId());
            insert.setLong(2, record.getSeq());
            insert.setString(3, record.getGraphId());
            insert.setString(4, record.getGraphVersion());
            insert.setString(5, record.getNodeId());
            insert.setString(6, record.getEdgeId());
            insert.setString(7, record.getStepId());
            insert.setInt(8, record.getStepIndex());
            insert.setString(9, record.getProcessorRef());
            insert.setString(10, record.getInputContextJson());
            insert.setString(11, record.getOutputJson());
            insert.setString(12, record.getStatus().name());
            insert.setString(13, record.getError());
            insert.setBoolean(14, record.isRestartable());
            insert.setLong(15, record.getStartedAtMillis());
            insert.setLong(16, record.getDurationMs());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append step trace for flow '"
                    + record.getFlowId() + "' seq " + record.getSeq(), e);
        }
    }

    @Override
    public List<StepTraceRecord> findByFlow(String flowId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT * FROM agentsgraph_step_trace WHERE flow_id = ? ORDER BY seq")) {
            select.setString(1, flowId);
            try (ResultSet rs = select.executeQuery()) {
                List<StepTraceRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(readRecord(rs));
                }
                return records;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load step traces for flow '" + flowId + "'", e);
        }
    }

    @Override
    public Optional<StepTraceRecord> find(String flowId, long seq) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT * FROM agentsgraph_step_trace WHERE flow_id = ? AND seq = ?")) {
            select.setString(1, flowId);
            select.setLong(2, seq);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.of(readRecord(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load step trace for flow '" + flowId
                    + "' seq " + seq, e);
        }
    }

    @Override
    public long deleteOlderThan(long startedBeforeEpochMillis) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM agentsgraph_step_trace WHERE started_at < ?")) {
            delete.setLong(1, startedBeforeEpochMillis);
            return delete.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete old step traces", e);
        }
    }

    private static StepTraceRecord readRecord(ResultSet rs) throws SQLException {
        StepTraceRecord record = new StepTraceRecord();
        record.setFlowId(rs.getString("flow_id"));
        record.setSeq(rs.getLong("seq"));
        record.setGraphId(rs.getString("graph_id"));
        record.setGraphVersion(rs.getString("graph_version"));
        record.setNodeId(rs.getString("node_id"));
        record.setEdgeId(rs.getString("edge_id"));
        record.setStepId(rs.getString("step_id"));
        record.setStepIndex(rs.getInt("step_index"));
        record.setProcessorRef(rs.getString("processor_ref"));
        record.setInputContextJson(rs.getString("input_context"));
        record.setOutputJson(rs.getString("output_raw"));
        record.setStatus(StepStatus.valueOf(rs.getString("status")));
        record.setError(rs.getString("error"));
        record.setRestartable(rs.getBoolean("restartable"));
        record.setStartedAtMillis(rs.getLong("started_at"));
        record.setDurationMs(rs.getLong("duration_ms"));
        return record;
    }
}
