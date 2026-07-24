package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Observes individual step executions inside an {@link Edge}. The orchestrator passes
 * {@link #NOOP} on normal runs and a {@link RecordingStepTracer} on debug runs - {@link Edge}
 * itself stays storage-agnostic.
 *
 * <p>{@code stepInput} is the exact (immutable) context snapshot the processor received, captured
 * before execution by construction - {@link ExecutionContext} never mutates.
 *
 * <p>{@link #compose} and {@link #isolated} are built on the command pattern: every callback is
 * reified as a {@link Command} and routed through a single dispatcher, so fan-out and exception
 * isolation live in ONE place instead of being repeated per callback.
 */
public interface StepTracer {

    StepTracer NOOP = dispatching(command -> {
    });

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

    /** One tracer callback (started/succeeded/failed), captured with its arguments as a command. */
    @FunctionalInterface
    interface Command {
        void executeOn(StepTracer tracer);
    }

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
        return dispatching(command -> {
            executeQuietly(command, first);
            executeQuietly(command, second);
        });
    }

    /** {@code delegate} with every callback exception swallowed - observers must not break flows. */
    static StepTracer isolated(StepTracer delegate) {
        return dispatching(command -> executeQuietly(command, delegate));
    }

    /** A tracer whose every callback is reified as a {@link Command} and handed to the dispatcher. */
    private static StepTracer dispatching(Consumer<Command> dispatcher) {
        return new StepTracer() {
            @Override
            public void stepStarted(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                      int stepCount, ExecutionContext stepInput) {
                dispatcher.accept(tracer ->
                        tracer.stepStarted(nodeId, edge, step, stepIndex, stepCount, stepInput));
            }

            @Override
            public void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                        ExecutionContext stepInput, Map<String, Object> rawOutput,
                                        long startedAtMillis, long durationMs) {
                dispatcher.accept(tracer -> tracer.stepSucceeded(nodeId, edge, step, stepIndex,
                        stepInput, rawOutput, startedAtMillis, durationMs));
            }

            @Override
            public void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                     ExecutionContext stepInput, Throwable failure,
                                     long startedAtMillis, long durationMs) {
                dispatcher.accept(tracer -> tracer.stepFailed(nodeId, edge, step, stepIndex,
                        stepInput, failure, startedAtMillis, durationMs));
            }
        };
    }

    private static void executeQuietly(Command command, StepTracer receiver) {
        try {
            command.executeOn(receiver);
        } catch (RuntimeException ignored) {
            // A tracing/progress listener must never break the flow itself.
        }
    }
}
