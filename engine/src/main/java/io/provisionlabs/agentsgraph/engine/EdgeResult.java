package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Outcome of executing an {@link Edge}: the updated context, tags, and any saved outputs. */
public final class EdgeResult {

    private final ExecutionContext updatedContext;
    private final List<String> tagsAdded;
    private final Map<String, Object> savedOutputs;

    public EdgeResult(ExecutionContext updatedContext, List<String> tagsAdded, Map<String, Object> savedOutputs) {
        this.updatedContext = updatedContext;
        this.tagsAdded = tagsAdded == null ? Collections.emptyList() : tagsAdded;
        this.savedOutputs = savedOutputs == null ? Collections.emptyMap() : savedOutputs;
    }

    public ExecutionContext getUpdatedContext() {
        return updatedContext;
    }

    public List<String> getTagsAdded() {
        return tagsAdded;
    }

    /** Union of each step's {@code outputToSave}-selected fields, for a caller-supplied {@link OutputSink}. */
    public Map<String, Object> getSavedOutputs() {
        return savedOutputs;
    }
}
