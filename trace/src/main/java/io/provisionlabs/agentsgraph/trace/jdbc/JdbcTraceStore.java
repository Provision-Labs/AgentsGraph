package io.provisionlabs.agentsgraph.trace.jdbc;

import io.provisionlabs.agentsgraph.trace.ExecutionEvent;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import io.provisionlabs.agentsgraph.trace.TraceRecord;
import io.provisionlabs.agentsgraph.trace.TraceStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Database-backed {@link TraceStore} for flow status, tags and telemetry counters, using a
 * {@code agentsgraph_execution_trace} table analogous in spirit to {@code agentsgraph_graph_config} - one row
 * per flow, tags stored as a delimited string for simple substring-based filtering.
 *
 * <p><b>Scope note:</b> unlike {@link io.provisionlabs.agentsgraph.trace.InMemoryTraceStore}, the
 * full per-node {@link ExecutionContext} audit-log snapshots are <em>not</em> persisted to the
 * database here - only status/tags/telemetry are durable. The audit log is kept in an in-process
 * cache so {@link #find} still returns it within the process that ran the flow (e.g. for a replay
 * right after execution), but it does not survive a restart or a different process. Persisting
 * arbitrary context payloads durably needs a schema tailored to what a given deployment puts in
 * its context, which is intentionally left to that deployment rather than baked into the
 * framework.
 */
public final class JdbcTraceStore implements TraceStore {

    private static final String TAG_DELIMITER = ",";

    private final DataSource dataSource;
    private final Map<String, List<ExecutionEvent>> auditLogCache = new ConcurrentHashMap<>();

    /** Ensures the trace schema (flow + step tables) exists - the store owns its own storage. */
    public JdbcTraceStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        createSchema(dataSource);
    }

    /**
     * Creates the {@code agentsgraph_execution_trace} (flow-level) and {@code
     * agentsgraph_step_trace} (step-level debug trace) tables if they don't already exist.
     */
    public static void createSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS agentsgraph_execution_trace (" +
                            "flow_id VARCHAR(128) PRIMARY KEY, " +
                            "tenant_id VARCHAR(128), " +
                            "status VARCHAR(32) NOT NULL, " +
                            "tags TEXT, " +
                            "error TEXT, " +
                            "step_count INT DEFAULT 0, " +
                            "token_cost BIGINT DEFAULT 0, " +
                            "duration_ms BIGINT DEFAULT 0, " +
                            "retry_attempts INT DEFAULT 0)");
            // Upgrade path for tables created before the error column existed (idempotent).
            statement.execute(
                    "ALTER TABLE agentsgraph_execution_trace ADD COLUMN IF NOT EXISTS error TEXT");
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
                            "status VARCHAR(32) NOT NULL, " +
                            "error TEXT, " +
                            "restartable BOOLEAN, " +
                            "started_at BIGINT, " +
                            "duration_ms BIGINT, " +
                            "PRIMARY KEY (flow_id, seq))");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create agentsgraph trace schema", e);
        }
    }

    @Override
    public TraceRecord startFlow(String flowId, String tenantId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO agentsgraph_execution_trace (flow_id, tenant_id, status, tags) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, flowId);
            insert.setString(2, tenantId);
            insert.setString(3, ExecutionStatus.RUNNING.name());
            insert.setString(4, "");
            insert.executeUpdate();
        } catch (SQLException e) {
            // Flow already started (e.g. retried call): fall through and return the existing row.
        }
        auditLogCache.computeIfAbsent(flowId, id -> new CopyOnWriteArrayList<>());
        return find(flowId).orElseThrow(() -> new IllegalStateException("Failed to start flow '" + flowId + "'"));
    }

    @Override
    public void appendEvent(String flowId, ExecutionEvent event) {
        auditLogCache.computeIfAbsent(flowId, id -> new CopyOnWriteArrayList<>()).add(event);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE agentsgraph_execution_trace SET step_count = step_count + 1 WHERE flow_id = ?")) {
            update.setString(1, flowId);
            requireRow(update.executeUpdate(), flowId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append event for flow '" + flowId + "'", e);
        }
    }

    @Override
    public void addTags(String flowId, Collection<String> tags) {
        if (tags.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            Set<String> merged = new LinkedHashSet<>(readTags(connection, flowId));
            merged.addAll(tags);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE agentsgraph_execution_trace SET tags = ? WHERE flow_id = ?")) {
                update.setString(1, String.join(TAG_DELIMITER, merged));
                update.setString(2, flowId);
                requireRow(update.executeUpdate(), flowId);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add tags for flow '" + flowId + "'", e);
        }
    }

    @Override
    public void updateStatus(String flowId, ExecutionStatus status) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE agentsgraph_execution_trace SET status = ? WHERE flow_id = ?")) {
            update.setString(1, status.name());
            update.setString(2, flowId);
            requireRow(update.executeUpdate(), flowId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update status for flow '" + flowId + "'", e);
        }
    }

    @Override
    public void recordError(String flowId, String error) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE agentsgraph_execution_trace SET error = ? WHERE flow_id = ?")) {
            update.setString(1, error);
            update.setString(2, flowId);
            requireRow(update.executeUpdate(), flowId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record error for flow '" + flowId + "'", e);
        }
    }

    @Override
    public Optional<TraceRecord> find(String flowId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT tenant_id, status, tags, error, step_count, token_cost, duration_ms, retry_attempts " +
                             "FROM agentsgraph_execution_trace WHERE flow_id = ?")) {
            select.setString(1, flowId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                TraceRecord record = new TraceRecord(flowId, rs.getString("tenant_id"));
                record.setStatus(ExecutionStatus.valueOf(rs.getString("status")));
                record.setError(rs.getString("error"));
                record.addTags(parseTags(rs.getString("tags")));
                record.getTelemetry().setDurationMs(rs.getLong("duration_ms"));
                record.getTelemetry().addTokenCost(rs.getLong("token_cost"));
                record.getTelemetry().setStepCount(rs.getInt("step_count"));
                record.getTelemetry().setRetryAttempts(rs.getInt("retry_attempts"));
                record.restoreAuditLog(auditLogCache.getOrDefault(flowId, List.of()));
                return Optional.of(record);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load trace record for flow '" + flowId + "'", e);
        }
    }

    @Override
    public List<TraceRecord> query(Set<String> tags, ExecutionStatus status, String tenantId) {
        StringBuilder sql = new StringBuilder("SELECT flow_id FROM agentsgraph_execution_trace WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        if (tenantId != null) {
            sql.append(" AND tenant_id = ?");
            params.add(tenantId);
        }
        List<String> flowIds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                select.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    flowIds.add(rs.getString("flow_id"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query trace records", e);
        }

        return flowIds.stream()
                .map(this::find)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(record -> tags == null || record.getTags().containsAll(tags))
                .collect(Collectors.toList());
    }

    @Override
    public void appendStep(StepTraceRecord record) {
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
    public List<StepTraceRecord> findSteps(String flowId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT * FROM agentsgraph_step_trace WHERE flow_id = ? ORDER BY seq")) {
            select.setString(1, flowId);
            try (ResultSet rs = select.executeQuery()) {
                List<StepTraceRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(readStepRecord(rs));
                }
                return records;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load step traces for flow '" + flowId + "'", e);
        }
    }

    @Override
    public Optional<StepTraceRecord> findStep(String flowId, long seq) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT * FROM agentsgraph_step_trace WHERE flow_id = ? AND seq = ?")) {
            select.setString(1, flowId);
            select.setLong(2, seq);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.of(readStepRecord(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load step trace for flow '" + flowId
                    + "' seq " + seq, e);
        }
    }

    @Override
    public long deleteStepsOlderThan(long startedBeforeEpochMillis) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM agentsgraph_step_trace WHERE started_at < ?")) {
            delete.setLong(1, startedBeforeEpochMillis);
            return delete.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete old step traces", e);
        }
    }

    private static StepTraceRecord readStepRecord(ResultSet rs) throws SQLException {
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
        record.setStatus(ExecutionStatus.valueOf(rs.getString("status")));
        record.setError(rs.getString("error"));
        record.setRestartable(rs.getBoolean("restartable"));
        record.setStartedAtMillis(rs.getLong("started_at"));
        record.setDurationMs(rs.getLong("duration_ms"));
        return record;
    }

    private Set<String> readTags(Connection connection, String flowId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT tags FROM agentsgraph_execution_trace WHERE flow_id = ?")) {
            select.setString(1, flowId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("No trace record started for flow '" + flowId + "'");
                }
                return parseTags(rs.getString("tags"));
            }
        }
    }

    private static Set<String> parseTags(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(TAG_DELIMITER))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void requireRow(int updatedRows, String flowId) {
        if (updatedRows == 0) {
            throw new IllegalArgumentException("No trace record started for flow '" + flowId + "'");
        }
    }
}
