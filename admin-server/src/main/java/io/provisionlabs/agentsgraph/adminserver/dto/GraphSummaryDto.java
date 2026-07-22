package io.provisionlabs.agentsgraph.adminserver.dto;

/** One row of the UI's graph list ({@code agentsgraph_graph_config}). */
public final class GraphSummaryDto {

    private final String id;
    private final String version;
    private final String entryNodeId;
    private final int nodeCount;
    private final int edgeCount;

    public GraphSummaryDto(String id, String version, String entryNodeId, int nodeCount, int edgeCount) {
        this.id = id;
        this.version = version;
        this.entryNodeId = entryNodeId;
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
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

    public int getNodeCount() {
        return nodeCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }
}
