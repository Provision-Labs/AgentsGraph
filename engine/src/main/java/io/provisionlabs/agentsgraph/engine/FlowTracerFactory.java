package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;

/**
 * Host-wide step observer: the orchestrator asks it for a {@link StepTracer} at the start of EVERY run of every
 * graph - {@code execute}, {@code executeAsync}, and the paths that pass no listener at all ({@code resumeFrom},
 * a human-task completion resuming a paused flow). That is the hook for cross-cutting concerns a per-call
 * listener can't cover: failure logging with the flow's identity, keeping the steps of failed runs for a
 * debugging UI, metrics.
 *
 * <p>Called once per run (a resumed flow is a new run under the same flow id). Return {@link StepTracer#NOOP}
 * to skip a run. The returned tracer is exception-isolated like any composed tracer, and a factory that throws
 * is treated as {@code NOOP} - an observer must never break a flow.
 */
@FunctionalInterface
public interface FlowTracerFactory {

    FlowTracerFactory NONE = (graph, initialContext) -> StepTracer.NOOP;

    /**
     * @param graph          the graph about to run
     * @param initialContext the run's starting context: its flow id, input data (for a resumed run - the
     *                       restored step input) and metadata
     */
    StepTracer forFlow(GraphDefinition graph, ExecutionContext initialContext);
}
