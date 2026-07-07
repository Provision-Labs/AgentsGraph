package com.provisionlabs.agentsgraph.engine;

import com.provisionlabs.agentsgraph.config.EdgeDefinition;
import com.provisionlabs.agentsgraph.config.StepDefinition;
import com.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime counterpart of an {@link EdgeDefinition}: executes its {@link StepDefinition} pipeline
 * in order.
 *
 * <p>Between steps, each step's raw output is projected twice, mirroring the legacy
 * docscan-pipeline's per-step {@code outputToNext}/{@code outputToSave} config:
 * <ul>
 *   <li>{@code outputToNext} selects which keys are merged into the {@link ExecutionContext}
 *       (via {@link ExecutionContext#withMergedState}) that the <em>next</em> step sees - an
 *       empty/absent list forwards the entire raw output, matching the legacy "no filter"
 *       behaviour. Unlike the legacy pipeline, this merges into the existing context rather than
 *       replacing it outright, since a graph {@code Processor} always receives a full context.</li>
 *   <li>{@code outputToSave} is an opt-in projection (an empty/absent list saves nothing) that
 *       collects keys into {@link EdgeResult#getSavedOutputs()}, independent of what continues
 *       down the pipeline, for a caller-supplied {@link OutputSink}.</li>
 * </ul>
 * Finally, the union of every step's raw output is folded into a new context snapshot via the
 * edge's {@code output_mapping}, exactly as before.
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
        Map<String, Object> savedOutputs = new LinkedHashMap<>();
        Map<String, Object> stepInput = Map.of();

        for (StepDefinition step : definition.getSteps()) {
            Processor processor = processorRegistry.resolve(step.getProcessorRef());
            ExecutionContext stepContext = stepInput.isEmpty() ? context : context.withMergedState(stepInput);

            Map<String, Object> stepOutput;
            try {
                stepOutput = processor.execute(stepContext, step);
            } catch (RuntimeException e) {
                throw new AgentsGraphException(
                        "Edge '" + definition.getId() + "' failed at step '" + step.getId() + "'", e);
            }
            if (stepOutput == null) {
                stepOutput = Map.of();
            }

            pipelineOutput.putAll(stepOutput);
            stepInput = projectForward(stepOutput, step.getOutputToNext());
            savedOutputs.putAll(projectForSave(stepOutput, step.getOutputToSave()));
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
        return new EdgeResult(updatedContext, definition.getTagsToAdd(), savedOutputs);
    }

    /** {@code outputToNext}: opt-out projection - an empty/absent list forwards everything. */
    private static Map<String, Object> projectForward(Map<String, Object> source, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return source;
        }
        return projectKeys(source, keys);
    }

    /** {@code outputToSave}: opt-in projection - an empty/absent list saves nothing. */
    private static Map<String, Object> projectForSave(Map<String, Object> source, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        return projectKeys(source, keys);
    }

    private static Map<String, Object> projectKeys(Map<String, Object> source, List<String> keys) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String key : keys) {
            if (source.containsKey(key)) {
                filtered.put(key, source.get(key));
            }
        }
        return filtered;
    }
}
