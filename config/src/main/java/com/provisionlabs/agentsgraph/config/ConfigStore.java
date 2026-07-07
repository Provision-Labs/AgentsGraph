package com.provisionlabs.agentsgraph.config;

import java.util.Optional;

/**
 * Access point for the Config Store layer. Implementations are expected to back this onto a
 * database table of versioned JSON documents so that graphs can be hot-reloaded without a
 * redeploy.
 */
public interface ConfigStore {

    /** Registers or overwrites a graph revision. */
    void putGraph(GraphDefinition graph);

    /** Looks up the latest known revision of a graph by id. */
    Optional<GraphDefinition> findGraph(String graphId);

    default GraphDefinition getGraph(String graphId) {
        return findGraph(graphId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown graph '" + graphId + "'"));
    }
}
