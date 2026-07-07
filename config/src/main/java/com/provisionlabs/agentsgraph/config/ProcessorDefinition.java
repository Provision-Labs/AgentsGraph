package com.provisionlabs.agentsgraph.config;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative description of a processor registered in the {@code processor_registry} (see the
 * {@code agentsgraph_processor} table): an id/name, whether it calls out to an external service
 * ({@code is_external}, used for health-check scheduling), the fully-qualified class implementing
 * {@link com.provisionlabs.agentsgraph.engine.Processor} to instantiate via reflection, and its
 * init-time params (e.g. a {@code processUrl}).
 */
public final class ProcessorDefinition {

    private final String id;
    private final String name;
    private final boolean external;
    private final String instanceClass;
    private final Map<String, Object> params;

    public ProcessorDefinition(String id, String name, boolean external, String instanceClass,
                                Map<String, Object> params) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name;
        this.external = external;
        this.instanceClass = Objects.requireNonNull(instanceClass, "instanceClass");
        this.params = params == null ? Collections.emptyMap() : Collections.unmodifiableMap(params);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isExternal() {
        return external;
    }

    public String getInstanceClass() {
        return instanceClass;
    }

    public Map<String, Object> getParams() {
        return params;
    }
}
