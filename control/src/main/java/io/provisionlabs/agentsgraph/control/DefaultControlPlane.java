package io.provisionlabs.agentsgraph.control;

import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ExecutionEvent;
import io.provisionlabs.agentsgraph.trace.TraceRecord;
import io.provisionlabs.agentsgraph.trace.TraceStore;

import java.util.List;
import java.util.Objects;

/** Reference {@link ControlPlane} implementation, reading directly from a {@link TraceStore}. */
public final class DefaultControlPlane implements ControlPlane {

    private final TraceStore traceStore;

    public DefaultControlPlane(TraceStore traceStore) {
        this.traceStore = Objects.requireNonNull(traceStore, "traceStore");
    }

    @Override
    public List<TraceRecord> queryExecutions(ExecutionQuery query) {
        return traceStore.query(query.getTags(), query.getStatus(), query.getTenantId());
    }

    @Override
    public ExecutionContext replay(String flowId, String fromNodeId) {
        TraceRecord record = traceStore.find(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown flow '" + flowId + "'"));
        for (ExecutionEvent event : record.getAuditLog()) {
            if (event.getNodeId().equals(fromNodeId)) {
                return event.getContextSnapshot();
            }
        }
        throw new IllegalArgumentException("Node '" + fromNodeId + "' never ran in flow '" + flowId + "'");
    }
}
