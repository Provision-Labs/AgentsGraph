package io.provisionlabs.agentsgraph.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Reference {@link ProcessorDefinitionStore} implementation backed by a concurrent in-memory map. */
public final class InMemoryProcessorDefinitionStore implements ProcessorDefinitionStore {

    private final Map<String, ProcessorDefinition> definitions = new ConcurrentHashMap<>();

    @Override
    public void put(ProcessorDefinition definition) {
        definitions.put(definition.getId(), definition);
    }

    @Override
    public Optional<ProcessorDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    @Override
    public List<ProcessorDefinition> findAll() {
        return new ArrayList<>(definitions.values());
    }
}
