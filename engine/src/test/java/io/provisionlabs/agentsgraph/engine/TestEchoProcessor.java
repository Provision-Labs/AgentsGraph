package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Loadable by {@link ProcessorLoader} via reflection (needs a public no-arg constructor); records
 * whether {@link #init} was called and echoes its params back so tests can assert on both.
 */
public class TestEchoProcessor implements Processor {

    private Map<String, Object> initParams;

    @Override
    public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
        Map<String, Object> out = new HashMap<>();
        out.put("initParams", initParams);
        out.put("stepId", step.getId());
        return out;
    }

    @Override
    public void init(Map<String, Object> params) {
        this.initParams = params;
    }
}
