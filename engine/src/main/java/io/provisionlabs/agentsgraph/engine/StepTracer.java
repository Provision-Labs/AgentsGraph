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

    /**
     * Fired right BEFORE a step's processor executes - the live-progress hook (e.g. a chatbot's
     * status line "edge X: step 2/5"). {@code stepCount} is the edge's total step count. Default
     * no-op so recording-only tracers don't have to implement it. The very first {@code
     * stepStarted} of an edge doubles as "edge started".
     */
    default void stepStarted(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                               int stepCount, ExecutionContext stepInput) {
    }

    void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                        ExecutionContext stepInput, Map<String, Object> rawOutput,
                        long startedAtMillis, long durationMs);

    void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                     ExecutionContext stepInput, Throwable failure,
                     long startedAtMillis, long durationMs);

    /**
     * Both tracers, in order - how a live progress listener runs ALONGSIDE debug-mode recording.
     * Every callback is exception-isolated (see {@link #isolated}): a tracer/listener failure
     * must never kill the flow itself.
     */
    static StepTracer compose(StepTracer first, StepTracer second) {
        if (first == null || first == NOOP) {
            return second == null || second == NOOP ? NOOP : isolated(second);
        }
        if (second == null || second == NOOP) {
            return isolated(first);
        }
        return new StepTracer() {
            @Override
            public void stepStarted(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                      int stepCount, ExecutionContext stepInput) {
                quietly(() -> first.stepStarted(nodeId, edge, step, stepIndex, stepCount, stepInput));
                quietly(() -> second.stepStarted(nodeId, edge, step, stepIndex, stepCount, stepInput));
            }

            @Override
            public void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                        ExecutionContext stepInput, Map<String, Object> rawOutput,
                                        long startedAtMillis, long durationMs) {
                quietly(() -> first.stepSucceeded(nodeId, edge, step, stepIndex, stepInput, rawOutput,
                        startedAtMillis, durationMs));
                quietly(() -> second.stepSucceeded(nodeId, edge, step, stepIndex, stepInput, rawOutput,
                        startedAtMillis, durationMs));
            }

            @Override
            public void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                     ExecutionContext stepInput, Throwable failure,
                                     long startedAtMillis, long durationMs) {
                quietly(() -> first.stepFailed(nodeId, edge, step, stepIndex, stepInput, failure,
                        startedAtMillis, durationMs));
                quietly(() -> second.stepFailed(nodeId, edge, step, stepIndex, stepInput, failure,
                        startedAtMillis, durationMs));
            }

            private void quietly(Runnable callback) {
                try {
                    callback.run();
                } catch (RuntimeException ignored) {
                    // A tracing/progress listener must never break the flow itself.
                }
            }
        };
    }

    /** {@code delegate} with every callback exception swallowed - observers must not break flows. */
    static StepTracer isolated(StepTracer delegate) {
        return new StepTracer() {
            @Override
            public void stepStarted(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                      int stepCount, ExecutionContext stepInput) {
                try {
                    delegate.stepStarted(nodeId, edge, step, stepIndex, stepCount, stepInput);
                } catch (RuntimeException ignored) {
                }
            }

            @Override
            public void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                        ExecutionContext stepInput, Map<String, Object> rawOutput,
                                        long startedAtMillis, long durationMs) {
                try {
                    delegate.stepSucceeded(nodeId, edge, step, stepIndex, stepInput, rawOutput,
                            startedAtMillis, durationMs);
                } catch (RuntimeException ignored) {
                }
            }

            @Override
            public void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                     ExecutionContext stepInput, Throwable failure,
                                     long startedAtMillis, long durationMs) {
                try {
                    delegate.stepFailed(nodeId, edge, step, stepIndex, stepInput, failure,
                            startedAtMillis, durationMs);
                } catch (RuntimeException ignored) {
                }
            }
        };
    }
}
