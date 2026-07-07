package com.provisionlabs.agentsgraph.engine;

import com.provisionlabs.agentsgraph.config.ProcessorDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflectively instantiates and registers {@link Processor}s described by {@link ProcessorDefinition}s,
 * mirroring how the legacy docscan-pipeline's {@code PipelineProcessorCreator} turns
 * {@code plb_pipeline_processor} rows into live processor instances: {@code instance_class} is
 * loaded via its no-arg constructor, {@link Processor#init(Map)} is called with {@code params},
 * and the instance is registered under the definition's {@code id}.
 */
public final class ProcessorLoader {

    private final ProcessorRegistry registry;

    public ProcessorLoader(ProcessorRegistry registry) {
        this.registry = registry;
    }

    /**
     * Loads every definition, registering successes into the {@link ProcessorRegistry}. A
     * definition that fails to load (missing class, wrong type, constructor/init failure) is
     * skipped and reported in {@link LoadResult#getFailures()} rather than aborting the batch,
     * mirroring the legacy loader's per-processor error isolation.
     */
    public LoadResult load(List<ProcessorDefinition> definitions) {
        Map<String, Processor> loaded = new LinkedHashMap<>();
        List<String> externalIds = new ArrayList<>();
        List<LoadFailure> failures = new ArrayList<>();

        for (ProcessorDefinition definition : definitions) {
            try {
                Class<?> clazz = Class.forName(definition.getInstanceClass());
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (!(instance instanceof Processor)) {
                    failures.add(new LoadFailure(definition.getId(),
                            "Class " + definition.getInstanceClass() + " does not implement Processor"));
                    continue;
                }

                Processor processor = (Processor) instance;
                processor.init(definition.getParams());
                registry.register(definition.getId(), processor);
                loaded.put(definition.getId(), processor);
                if (definition.isExternal()) {
                    externalIds.add(definition.getId());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                failures.add(new LoadFailure(definition.getId(), String.valueOf(e.getMessage())));
            }
        }
        return new LoadResult(loaded, externalIds, failures);
    }

    public static final class LoadResult {
        /** An empty result, e.g. as the initial state before any {@link ProcessorLoader#load} call. */
        public static final LoadResult EMPTY = new LoadResult(Map.of(), List.of(), List.of());

        private final Map<String, Processor> loaded;
        private final List<String> externalProcessorIds;
        private final List<LoadFailure> failures;

        LoadResult(Map<String, Processor> loaded, List<String> externalProcessorIds, List<LoadFailure> failures) {
            this.loaded = loaded;
            this.externalProcessorIds = externalProcessorIds;
            this.failures = failures;
        }

        public Map<String, Processor> getLoaded() {
            return loaded;
        }

        public List<String> getExternalProcessorIds() {
            return externalProcessorIds;
        }

        public List<LoadFailure> getFailures() {
            return failures;
        }
    }

    public static final class LoadFailure {
        private final String processorId;
        private final String reason;

        LoadFailure(String processorId, String reason) {
            this.processorId = processorId;
            this.reason = reason;
        }

        public String getProcessorId() {
            return processorId;
        }

        public String getReason() {
            return reason;
        }
    }
}
