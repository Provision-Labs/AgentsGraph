package com.provisionlabs.agentsgraph.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Layer 1 - Config Store: the single source of truth for a workflow topology. A versioned,
 * declarative registry of {@link NodeDefinition}s and {@link EdgeDefinition}s, meant to be
 * stored (and hot-reloaded) as JSON in a database.
 */
public final class GraphDefinition {

    private final String id;
    private final String version;
    private final String entryNodeId;
    private final Map<String, NodeDefinition> nodes;
    private final Map<String, EdgeDefinition> edges;
    private final List<String> templates;

    private GraphDefinition(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.version = Objects.requireNonNull(builder.version, "version");
        this.entryNodeId = Objects.requireNonNull(builder.entryNodeId, "entryNodeId");
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.nodes));
        this.edges = Collections.unmodifiableMap(new LinkedHashMap<>(builder.edges));
        this.templates = Collections.unmodifiableList(new ArrayList<>(builder.templates));

        if (!nodes.containsKey(entryNodeId)) {
            throw new IllegalArgumentException("Unknown entryNodeId '" + entryNodeId + "' for graph '" + id + "'");
        }
    }

    public static Builder builder(String id, String version) {
        return new Builder(id, version);
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getEntryNodeId() {
        return entryNodeId;
    }

    public NodeDefinition getNode(String nodeId) {
        NodeDefinition node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node '" + nodeId + "' in graph '" + id + "'");
        }
        return node;
    }

    public EdgeDefinition getEdge(String edgeId) {
        EdgeDefinition edge = edges.get(edgeId);
        if (edge == null) {
            throw new IllegalArgumentException("Unknown edge '" + edgeId + "' in graph '" + id + "'");
        }
        return edge;
    }

    public Map<String, NodeDefinition> getNodes() {
        return nodes;
    }

    public Map<String, EdgeDefinition> getEdges() {
        return edges;
    }

    /**
     * Free-text/template tags used by a {@link com.provisionlabs.agentsgraph.control.GraphClassifier}
     * to pick which graph should handle a given input (e.g. {@code "file:accountant"}), mirroring
     * the legacy docscan-pipeline's per-pipeline {@code templates} list.
     */
    public List<String> getTemplates() {
        return templates;
    }

    public static final class Builder {
        private final String id;
        private final String version;
        private String entryNodeId;
        private final Map<String, NodeDefinition> nodes = new LinkedHashMap<>();
        private final Map<String, EdgeDefinition> edges = new LinkedHashMap<>();
        private final List<String> templates = new ArrayList<>();

        private Builder(String id, String version) {
            this.id = id;
            this.version = version;
        }

        public Builder entryNodeId(String entryNodeId) {
            this.entryNodeId = entryNodeId;
            return this;
        }

        public Builder node(NodeDefinition node) {
            this.nodes.put(node.getId(), node);
            return this;
        }

        public Builder edge(EdgeDefinition edge) {
            this.edges.put(edge.getId(), edge);
            return this;
        }

        public Builder template(String template) {
            this.templates.add(template);
            return this;
        }

        public Builder templates(List<String> templates) {
            this.templates.addAll(templates);
            return this;
        }

        public GraphDefinition build() {
            return new GraphDefinition(this);
        }
    }
}
