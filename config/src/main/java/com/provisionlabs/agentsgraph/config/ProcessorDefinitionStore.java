package com.provisionlabs.agentsgraph.config;

import java.util.List;
import java.util.Optional;

/**
 * Access point for processor definitions (the {@code plb_pipeline_processor} equivalent),
 * storable either in memory or in a database - see {@code InMemoryProcessorDefinitionStore} and
 * the JDBC-backed implementation.
 */
public interface ProcessorDefinitionStore {

    void put(ProcessorDefinition definition);

    Optional<ProcessorDefinition> find(String id);

    List<ProcessorDefinition> findAll();
}
