package com.provisionlabs.agentsgraph.engine;

import com.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.Collections;
import java.util.List;

/** Outcome of executing an {@link Edge}: the updated context plus the tags it wants recorded. */
public final class EdgeResult {

    private final ExecutionContext updatedContext;
    private final List<String> tagsAdded;

    public EdgeResult(ExecutionContext updatedContext, List<String> tagsAdded) {
        this.updatedContext = updatedContext;
        this.tagsAdded = tagsAdded == null ? Collections.emptyList() : tagsAdded;
    }

    public ExecutionContext getUpdatedContext() {
        return updatedContext;
    }

    public List<String> getTagsAdded() {
        return tagsAdded;
    }
}
