package io.provisionlabs.agentsgraph.interaction;

import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;

import java.util.Map;

/**
 * Does nothing. For edges whose only job is routing/tags - e.g. the terminal edge of the review
 * branch that adds the {@code review_pending} tag (an edge must have at least one step).
 */
public final class NoopProcessor implements Processor {

    @Override
    public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
        return Map.of();
    }
}
