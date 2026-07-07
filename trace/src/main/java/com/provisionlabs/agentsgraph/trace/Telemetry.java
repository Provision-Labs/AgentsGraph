package com.provisionlabs.agentsgraph.trace;

/** Per-flow telemetry counters surfaced on dashboards (conversion, latency, cost, retries). */
public final class Telemetry {

    private long durationMs;
    private long tokenCost;
    private int stepCount;
    private int retryAttempts;

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public long getTokenCost() {
        return tokenCost;
    }

    public void addTokenCost(long tokenCost) {
        this.tokenCost += tokenCost;
    }

    public int getStepCount() {
        return stepCount;
    }

    public void incrementStepCount() {
        this.stepCount++;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public void incrementRetryAttempts() {
        this.retryAttempts++;
    }
}
