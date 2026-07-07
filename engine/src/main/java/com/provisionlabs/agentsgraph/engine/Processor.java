package com.provisionlabs.agentsgraph.engine;

import com.provisionlabs.agentsgraph.config.StepDefinition;
import com.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.Map;

/**
 * A single unit of work inside an {@link com.provisionlabs.agentsgraph.config.EdgeDefinition}
 * pipeline: a business rule, an external call, an LLM prompt, a validation step, etc. Registered
 * in a {@link ProcessorRegistry} under {@link StepDefinition#getProcessorRef()}.
 */
public interface Processor {

    Map<String, Object> execute(ExecutionContext context, StepDefinition step);
}
