package com.provisionlabs.agentsgraph.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Reference {@link OutputSink} that keeps every saved-output batch per flow, for tests/inspection. */
public final class InMemoryOutputSink implements OutputSink {

    private final Map<String, List<Map<String, Object>>> saved = new ConcurrentHashMap<>();

    @Override
    public void save(String flowId, Map<String, Object> outputs) {
        if (outputs.isEmpty()) {
            return;
        }
        saved.computeIfAbsent(flowId, id -> new CopyOnWriteArrayList<>()).add(new LinkedHashMap<>(outputs));
    }

    public List<Map<String, Object>> getSaved(String flowId) {
        return saved.getOrDefault(flowId, List.of());
    }
}
