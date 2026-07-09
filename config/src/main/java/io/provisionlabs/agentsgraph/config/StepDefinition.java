package io.provisionlabs.agentsgraph.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single step of an {@link EdgeDefinition} pipeline, resolved against the {@code processor_registry}.
 *
 * <p>{@code outputToNext} and {@code outputToSave} project the raw output produced by this step's
 * {@link io.provisionlabs.agentsgraph.engine.Processor}: {@code outputToNext} selects which keys
 * are threaded into the next step's input (an empty/absent list forwards the entire output,
 * matching the legacy docscan-pipeline {@code outputToNext} semantics), while {@code outputToSave}
 * selects which keys are collected into the edge's saved-output set for persistence regardless of
 * what continues down the pipeline.
 */
public final class StepDefinition {

    private final String id;
    private final String processorRef;
    private final Map<String, Object> params;
    private final List<String> outputToNext;
    private final List<String> outputToSave;

    public StepDefinition(String id, String processorRef, Map<String, Object> params) {
        this(id, processorRef, params, Collections.emptyList(), Collections.emptyList());
    }

    public StepDefinition(String id, String processorRef, Map<String, Object> params,
                           List<String> outputToNext, List<String> outputToSave) {
        this.id = Objects.requireNonNull(id, "id");
        this.processorRef = Objects.requireNonNull(processorRef, "processorRef");
        this.params = params == null ? Collections.emptyMap() : Collections.unmodifiableMap(params);
        this.outputToNext = outputToNext == null ? Collections.emptyList() : Collections.unmodifiableList(outputToNext);
        this.outputToSave = outputToSave == null ? Collections.emptyList() : Collections.unmodifiableList(outputToSave);
    }

    public String getId() {
        return id;
    }

    public String getProcessorRef() {
        return processorRef;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    /** Keys of this step's output forwarded to the next step's input; empty means "forward everything". */
    public List<String> getOutputToNext() {
        return outputToNext;
    }

    /** Keys of this step's output collected for persistence via the edge's {@code OutputSink}. */
    public List<String> getOutputToSave() {
        return outputToSave;
    }
}
