package com.provisionlabs.agentsgraph.engine;

import com.provisionlabs.agentsgraph.config.EdgeDefinition;
import com.provisionlabs.agentsgraph.config.StepDefinition;
import com.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime counterpart of an {@link EdgeDefinition}: executes its {@link StepDefinition} pipeline
 * in order, threading the output of each step into the next, then folds the pipeline's combined
 * output into a new {@link ExecutionContext} snapshot via {@code output_mapping}.
 */
public final class Edge {

    private final EdgeDefinition definition;
    private final ProcessorRegistry processorRegistry;

    public Edge(EdgeDefinition definition, ProcessorRegistry processorRegistry) {
        this.definition = definition;
        this.processorRegistry = processorRegistry;
    }

    public EdgeDefinition getDefinition() {
        return definition;
    }

    public EdgeResult execute(ExecutionContext context) {
        Map<String, Object> pipelineOutput = new LinkedHashMap<>();
        for (StepDefinition step : definition.getSteps()) {
            Processor processor = processorRegistry.resolve(step.getProcessorRef());
            try {
                Map<String, Object> stepOutput = processor.execute(context, step);
                if (stepOutput != null) {
                    pipelineOutput.putAll(stepOutput);
                }
            } catch (RuntimeException e) {
                throw new AgentsGraphException(
                        "Edge '" + definition.getId() + "' failed at step '" + step.getId() + "'", e);
            }
        }

        Map<String, Object> mappedState = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : definition.getOutputMapping().entrySet()) {
            if (pipelineOutput.containsKey(mapping.getKey())) {
                mappedState.put(mapping.getValue(), pipelineOutput.get(mapping.getKey()));
            }
        }
        if (definition.getOutputMapping().isEmpty()) {
            mappedState.putAll(pipelineOutput);
        }

        ExecutionContext updatedContext = context.withMergedState(mappedState);
        return new EdgeResult(updatedContext, definition.getTagsToAdd());
    }
}
