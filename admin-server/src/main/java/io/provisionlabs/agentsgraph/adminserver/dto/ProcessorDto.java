package io.provisionlabs.agentsgraph.adminserver.dto;

import java.util.Map;

/**
 * One processor as the UI sees it: an {@code agentsgraph_processor} row, or - when a graph step
 * references a ref with no row - a programmatic processor (registered in code, e.g. via a Spring
 * {@code <map>}), reported with {@code programmatic=true} and no instance class.
 */
public final class ProcessorDto {

    private final String id;
    private final String name;
    private final boolean external;
    private final String instanceClass;
    private final Map<String, Object> params;
    private final boolean programmatic;

    public ProcessorDto(String id, String name, boolean external, String instanceClass,
                         Map<String, Object> params, boolean programmatic) {
        this.id = id;
        this.name = name;
        this.external = external;
        this.instanceClass = instanceClass;
        this.params = params;
        this.programmatic = programmatic;
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

    public boolean isProgrammatic() {
        return programmatic;
    }
}
