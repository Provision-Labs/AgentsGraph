package io.provisionlabs.agentsgraph.adminserver.dto;

/**
 * One row of {@code agentsgraph_step_trace} for the UI's debug view. {@code inputContext} and
 * {@code output} are the recorded snapshots parsed back into plain JSON structures (maps/lists/
 * scalars), so the UI renders them as JSON trees rather than escaped strings.
 */
public final class StepDto {

    private final String flowId;
    private final long seq;
    private final String graphId;
    private final String graphVersion;
    private final String nodeId;
    private final String edgeId;
    private final String stepId;
    private final int stepIndex;
    private final String processorRef;
    private final Object inputContext;
    private final Object output;
    private final String status;
    private final String error;
    private final boolean restartable;
    private final long startedAtMillis;
    private final long durationMs;

    public StepDto(String flowId, long seq, String graphId, String graphVersion, String nodeId,
                    String edgeId, String stepId, int stepIndex, String processorRef,
                    Object inputContext, Object output, String status, String error,
                    boolean restartable, long startedAtMillis, long durationMs) {
        this.flowId = flowId;
        this.seq = seq;
        this.graphId = graphId;
        this.graphVersion = graphVersion;
        this.nodeId = nodeId;
        this.edgeId = edgeId;
        this.stepId = stepId;
        this.stepIndex = stepIndex;
        this.processorRef = processorRef;
        this.inputContext = inputContext;
        this.output = output;
        this.status = status;
        this.error = error;
        this.restartable = restartable;
        this.startedAtMillis = startedAtMillis;
        this.durationMs = durationMs;
    }

    public String getFlowId() {
        return flowId;
    }

    public long getSeq() {
        return seq;
    }

    public String getGraphId() {
        return graphId;
    }

    public String getGraphVersion() {
        return graphVersion;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getEdgeId() {
        return edgeId;
    }

    public String getStepId() {
        return stepId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public String getProcessorRef() {
        return processorRef;
    }

    public Object getInputContext() {
        return inputContext;
    }

    public Object getOutput() {
        return output;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public boolean isRestartable() {
        return restartable;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
