package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.Map;

/**
 * A single unit of work inside an {@link io.provisionlabs.agentsgraph.config.EdgeDefinition}
 * pipeline: a business rule, an external call, an LLM prompt, a validation step, etc. Registered
 * in a {@link ProcessorRegistry} under {@link StepDefinition#getProcessorRef()}.
 */
public interface Processor {

    Map<String, Object> execute(ExecutionContext context, StepDefinition step);

    /** Called once after construction, with the processor's {@code params}, before first use. */
    default void init(Map<String, Object> params) {
    }

    /** Called on shutdown to release resources (connections, thread pools, ...). */
    default void close() {
    }

    /** Overridden by processors backed by an external service to report reachability. */
    default boolean isHealthy() {
        return true;
    }
}
