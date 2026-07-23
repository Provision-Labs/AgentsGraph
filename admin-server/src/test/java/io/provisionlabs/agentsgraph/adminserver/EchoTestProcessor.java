package io.provisionlabs.agentsgraph.adminserver;

import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;

import java.util.Map;

/**
 * Loaded REFLECTIVELY by the engine from its {@code agentsgraph_processor} row (see
 * {@code sql/admin-server-db-test-data.sql}) - proving the DB-driven processor loading works
 * through the whole booted server, not just a programmatic registration.
 */
public class EchoTestProcessor implements Processor {

    @Override
    public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
        return Map.of("answer", "echo: " + context.getInputData().get("text"));
    }
}
