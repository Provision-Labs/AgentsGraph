package io.provisionlabs.agentsgraph.web.dto;

import java.util.Set;

/** One flow of {@code agentsgraph_execution_trace} for the UI's executions table. */
public final class ExecutionDto {

    private final String flowId;
    private final String tenantId;
    private final String status;
    private final Set<String> tags;
    private final String error;
    private final int stepCount;
    private final long tokenCost;
    private final long durationMs;
    private final int retryAttempts;

    public ExecutionDto(String flowId, String tenantId, String status, Set<String> tags, String error,
                         int stepCount, long tokenCost, long durationMs, int retryAttempts) {
        this.flowId = flowId;
        this.tenantId = tenantId;
        this.status = status;
        this.tags = tags;
        this.error = error;
        this.stepCount = stepCount;
        this.tokenCost = tokenCost;
        this.durationMs = durationMs;
        this.retryAttempts = retryAttempts;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getStatus() {
        return status;
    }

    public Set<String> getTags() {
        return tags;
    }

    public String getError() {
        return error;
    }

    public int getStepCount() {
        return stepCount;
    }

    public long getTokenCost() {
        return tokenCost;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }
}
