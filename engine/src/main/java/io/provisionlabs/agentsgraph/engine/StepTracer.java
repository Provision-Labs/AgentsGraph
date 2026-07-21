package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.Map;

/**
 * Observes individual step executions inside an {@link Edge}. The orchestrator passes
 * {@link #NOOP} on normal runs and a {@link RecordingStepTracer} on debug runs - {@link Edge}
 * itself stays storage-agnostic.
 *
 * <p>{@code stepInput} is the exact (immutable) context snapshot the processor received, captured
 * before execution by construction - {@link ExecutionContext} never mutates.
 */
public interface StepTracer {

    StepTracer NOOP = new StepTracer() {
        @Override
        public void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                    ExecutionContext stepInput, Map<String, Object> rawOutput,
                                    long startedAtMillis, long durationMs) {
        }

        @Override
        public void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                 ExecutionContext stepInput, Throwable failure,
                                 long startedAtMillis, long durationMs) {
        }
    };

    void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                        ExecutionContext stepInput, Map<String, Object> rawOutput,
                        long startedAtMillis, long durationMs);

    void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                     ExecutionContext stepInput, Throwable failure,
                     long startedAtMillis, long durationMs);
}
