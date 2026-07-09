package io.provisionlabs.agentsgraph.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference {@link ConfigStore} implementation backed by a concurrent in-memory map. Suitable for
 * tests and as a starting point for a real, DB-backed, hot-reloadable store.
 */
public final class InMemoryConfigStore implements ConfigStore {

    private final Map<String, GraphDefinition> graphs = new ConcurrentHashMap<>();

    @Override
    public void putGraph(GraphDefinition graph) {
        graphs.put(graph.getId(), graph);
    }

    @Override
    public Optional<GraphDefinition> findGraph(String graphId) {
        return Optional.ofNullable(graphs.get(graphId));
    }

    @Override
    public List<GraphDefinition> findAll() {
        return new ArrayList<>(graphs.values());
    }
}
