package com.provisionlabs.agentsgraph.trace.jdbc;

import com.provisionlabs.agentsgraph.trace.ExecutionEvent;
import com.provisionlabs.agentsgraph.trace.ExecutionStatus;
import com.provisionlabs.agentsgraph.trace.TraceRecord;
import com.provisionlabs.agentsgraph.trace.TraceStore;

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
 * <p><b>Scope note:</b> unlike {@link com.provisionlabs.agentsgraph.trace.InMemoryTraceStore}, the
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

    public JdbcTraceStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Creates the {@code agentsgraph_execution_trace} table if it doesn't already exist. */
    public static void createSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS agentsgraph_execution_trace (" +
                            "flow_id VARCHAR(128) PRIMARY KEY, " +
                            "tenant_id VARCHAR(128), " +
                            "status VARCHAR(32) NOT NULL, " +
                            "tags TEXT, " +
                            "step_count INT DEFAULT 0, " +
                            "token_cost BIGINT DEFAULT 0, " +
                            "duration_ms BIGINT DEFAULT 0, " +
                            "retry_attempts INT DEFAULT 0)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create agentsgraph_execution_trace schema", e);
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
    public Optional<TraceRecord> find(String flowId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT tenant_id, status, tags, step_count, token_cost, duration_ms, retry_attempts " +
                             "FROM agentsgraph_execution_trace WHERE flow_id = ?")) {
            select.setString(1, flowId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                TraceRecord record = new TraceRecord(flowId, rs.getString("tenant_id"));
                record.setStatus(ExecutionStatus.valueOf(rs.getString("status")));
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
