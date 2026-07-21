package io.provisionlabs.agentsgraph.trace;

/**
 * One row of the step-level debug trace: a single step execution inside an edge, with the FULL
 * context the step saw on input ({@link #getInputContextJson()}) and the raw output map it
 * produced ({@link #getOutputJson()}), both serialized by {@link ContextJsonCodec}.
 *
 * <p>The input snapshot is deliberately the complete context rather than a delta - it makes every
 * step an independent restart point: {@code AgentsGraphEngine.resumeFrom(flowId, seq)} rebuilds
 * the context from this snapshot and re-runs the flow from exactly this step on exactly this
 * data. {@link #isRestartable()} is {@code false} when the snapshot had to drop or truncate a
 * value (see {@link ContextJsonCodec}) - such a record is still useful for debugging, but can't
 * be resumed from.
 */
public final class StepTraceRecord {

    private String flowId;
    private long seq;
    private String graphId;
    private String graphVersion;
    private String nodeId;
    private String edgeId;
    private String stepId;
    private int stepIndex;
    private String processorRef;
    private String inputContextJson;
    private String outputJson;
    private StepStatus status = StepStatus.OK;
    private String error;
    private boolean restartable = true;
    private long startedAtMillis;
    private long durationMs;

    public String getFlowId() {
        return flowId;
    }

    public void setFlowId(String flowId) {
        this.flowId = flowId;
    }

    /** Monotonic per-flow step counter (0-based) - the global execution order within the flow. */
    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getGraphId() {
        return graphId;
    }

    public void setGraphId(String graphId) {
        this.graphId = graphId;
    }

    /** Graph version the step executed against - checked (with a warning) on resume. */
    public String getGraphVersion() {
        return graphVersion;
    }

    public void setGraphVersion(String graphVersion) {
        this.graphVersion = graphVersion;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getEdgeId() {
        return edgeId;
    }

    public void setEdgeId(String edgeId) {
        this.edgeId = edgeId;
    }

    public String getStepId() {
        return stepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    /** Position of the step within its edge's pipeline (0-based) - the resume entry point. */
    public int getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(int stepIndex) {
        this.stepIndex = stepIndex;
    }

    public String getProcessorRef() {
        return processorRef;
    }

    public void setProcessorRef(String processorRef) {
        this.processorRef = processorRef;
    }

    /** Full context snapshot (input_data + accumulated_state + metadata) the step saw on input. */
    public String getInputContextJson() {
        return inputContextJson;
    }

    public void setInputContextJson(String inputContextJson) {
        this.inputContextJson = inputContextJson;
    }

    /**
     * The step's RAW output map, before {@code output_to_next}/{@code output_to_save} projection
     * (both projections are deterministic from graph config, so they can be replayed from this).
     * {@code null} for a {@link StepStatus#FAILED} step.
     */
    public String getOutputJson() {
        return outputJson;
    }

    public void setOutputJson(String outputJson) {
        this.outputJson = outputJson;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
    }

    /** Full stack trace when the step failed; {@code null} otherwise. */
    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    /**
     * {@code false} when the input snapshot dropped or truncated a value (unserializable object,
     * value over the codec's size limit) - the record documents the step but can't seed a resume.
     */
    public boolean isRestartable() {
        return restartable;
    }

    public void setRestartable(boolean restartable) {
        this.restartable = restartable;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public void setStartedAtMillis(long startedAtMillis) {
        this.startedAtMillis = startedAtMillis;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    @Override
    public String toString() {
        return "StepTraceRecord{flow=" + flowId + ", seq=" + seq + ", node=" + nodeId
                + ", edge=" + edgeId + ", step=" + stepId + "[" + stepIndex + "], status=" + status
                + ", restartable=" + restartable + '}';
    }
}
