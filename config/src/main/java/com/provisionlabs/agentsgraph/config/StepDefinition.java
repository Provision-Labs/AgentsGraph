package com.provisionlabs.agentsgraph.config;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** A single step of an {@link EdgeDefinition} pipeline, resolved against the {@code processor_registry}. */
public final class StepDefinition {

    private final String id;
    private final String processorRef;
    private final Map<String, Object> params;

    public StepDefinition(String id, String processorRef, Map<String, Object> params) {
        this.id = Objects.requireNonNull(id, "id");
        this.processorRef = Objects.requireNonNull(processorRef, "processorRef");
        this.params = params == null ? Collections.emptyMap() : Collections.unmodifiableMap(params);
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
}
