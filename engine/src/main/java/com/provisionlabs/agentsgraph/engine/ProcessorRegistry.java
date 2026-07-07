package com.provisionlabs.agentsgraph.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves a {@code step.processor_ref} to the {@link Processor} implementation that runs it. */
public final class ProcessorRegistry {

    private final Map<String, Processor> processors = new ConcurrentHashMap<>();

    public ProcessorRegistry register(String ref, Processor processor) {
        processors.put(ref, processor);
        return this;
    }

    public Processor resolve(String ref) {
        Processor processor = processors.get(ref);
        if (processor == null) {
            throw new AgentsGraphException("No processor registered for ref '" + ref + "'");
        }
        return processor;
    }
}
