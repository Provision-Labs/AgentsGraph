package io.provisionlabs.agentsgraph.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An {@code Edge}: an executable pipeline of {@link StepDefinition}s. Receives the payload mapped
 * by the originating {@link NodeDefinition}, executes its steps in order and returns a structured
 * output that is merged back into the {@link io.provisionlabs.agentsgraph.context.ExecutionContext}.
 */
public final class EdgeDefinition {

    private final String id;
    private final List<StepDefinition> steps;
    private final Map<String, String> outputMapping;
    private final List<String> tagsToAdd;
    private final String nextNodeId;

    private EdgeDefinition(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.steps = Collections.unmodifiableList(new ArrayList<>(builder.steps));
        this.outputMapping = Collections.unmodifiableMap(new LinkedHashMap<>(builder.outputMapping));
        this.tagsToAdd = Collections.unmodifiableList(new ArrayList<>(builder.tagsToAdd));
        this.nextNodeId = builder.nextNodeId;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String getId() {
        return id;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public Map<String, String> getOutputMapping() {
        return outputMapping;
    }

    public List<String> getTagsToAdd() {
        return tagsToAdd;
    }

    /** Id of the {@link NodeDefinition} to evaluate next, or {@code null} if this edge terminates the flow. */
    public String getNextNodeId() {
        return nextNodeId;
    }

    public static final class Builder {
        private final String id;
        private final List<StepDefinition> steps = new ArrayList<>();
        private final Map<String, String> outputMapping = new LinkedHashMap<>();
        private final List<String> tagsToAdd = new ArrayList<>();
        private String nextNodeId;

        private Builder(String id) {
            this.id = id;
        }

        public Builder step(StepDefinition step) {
            this.steps.add(step);
            return this;
        }

        public Builder outputMapping(String from, String to) {
            this.outputMapping.put(from, to);
            return this;
        }

        public Builder tagToAdd(String tag) {
            this.tagsToAdd.add(tag);
            return this;
        }

        public Builder nextNodeId(String nextNodeId) {
            this.nextNodeId = nextNodeId;
            return this;
        }

        public EdgeDefinition build() {
            return new EdgeDefinition(this);
        }
    }
}
