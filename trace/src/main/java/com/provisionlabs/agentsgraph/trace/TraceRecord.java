package com.provisionlabs.agentsgraph.trace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer 4 - Status & Trace Store record for a single flow execution: its lifecycle status,
 * dynamic tags, telemetry counters and the full audit log of routing decisions.
 */
public final class TraceRecord {

    private final String flowId;
    private final String tenantId;
    private volatile ExecutionStatus status = ExecutionStatus.RUNNING;
    private final Set<String> tags = new LinkedHashSet<>();
    private final Telemetry telemetry = new Telemetry();
    private final List<ExecutionEvent> auditLog = new ArrayList<>();

    public TraceRecord(String flowId, String tenantId) {
        this.flowId = flowId;
        this.tenantId = tenantId;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public Set<String> getTags() {
        return tags;
    }

    public void addTags(Iterable<String> newTags) {
        newTags.forEach(tags::add);
    }

    public Telemetry getTelemetry() {
        return telemetry;
    }

    public List<ExecutionEvent> getAuditLog() {
        return auditLog;
    }

    public void appendEvent(ExecutionEvent event) {
        auditLog.add(event);
        telemetry.incrementStepCount();
    }

    /**
     * Adds previously-recorded events to the audit log without touching telemetry counters, for
     * store implementations (e.g. {@code JdbcTraceStore}) reconstructing a record whose counters
     * already come from a persisted row.
     */
    public void restoreAuditLog(List<ExecutionEvent> events) {
        auditLog.addAll(events);
    }
}
