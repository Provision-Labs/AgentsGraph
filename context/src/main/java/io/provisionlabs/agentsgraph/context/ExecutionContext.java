package io.provisionlabs.agentsgraph.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Layer 2 - Execution Context: a read-only data container that flows through the graph. Every
 * step of the runtime produces a new snapshot rather than mutating an existing one, which is what
 * makes {@link io.provisionlabs.agentsgraph.trace.TraceStore} audit trails and replay possible.
 */
public final class ExecutionContext {

    private final String flowId;
    private final String traceId;
    private final String parentId;
    private final Map<String, Object> inputData;
    private final Map<String, Object> accumulatedState;
    private final Map<String, Object> metadata;
    private final String contextSchema;

    private ExecutionContext(String flowId, String traceId, String parentId,
                              Map<String, Object> inputData, Map<String, Object> accumulatedState,
                              Map<String, Object> metadata, String contextSchema) {
        this.flowId = Objects.requireNonNull(flowId, "flowId");
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.parentId = parentId;
        this.inputData = Collections.unmodifiableMap(new LinkedHashMap<>(inputData));
        this.accumulatedState = Collections.unmodifiableMap(new LinkedHashMap<>(accumulatedState));
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.contextSchema = Objects.requireNonNull(contextSchema, "contextSchema");
    }

    public static ExecutionContext newFlow(Map<String, Object> inputData, Map<String, Object> metadata) {
        String flowId = "flow_" + UUID.randomUUID();
        return new ExecutionContext(flowId, flowId, null, inputData, Collections.emptyMap(), metadata, "v1.0");
    }

    public String getFlowId() {
        return flowId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getParentId() {
        return parentId;
    }

    public Map<String, Object> getInputData() {
        return inputData;
    }

    public Map<String, Object> getAccumulatedState() {
        return accumulatedState;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getContextSchema() {
        return contextSchema;
    }

    /** Produces a new snapshot with additional entries merged into {@code accumulated_state}. */
    public ExecutionContext withMergedState(Map<String, Object> additionalState) {
        Map<String, Object> merged = new LinkedHashMap<>(accumulatedState);
        merged.putAll(additionalState);
        return new ExecutionContext(flowId, traceId, parentId, inputData, merged, metadata, contextSchema);
    }

    /** Produces a new snapshot representing a child step, with {@code this.flowId} as its parent. */
    public ExecutionContext asChild() {
        return new ExecutionContext(
                "flow_" + UUID.randomUUID(), traceId, flowId, inputData, accumulatedState, metadata, contextSchema);
    }
}
