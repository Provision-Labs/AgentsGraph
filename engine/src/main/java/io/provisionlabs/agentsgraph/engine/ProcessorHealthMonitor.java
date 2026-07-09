package io.provisionlabs.agentsgraph.engine;

import java.util.List;
import java.util.Map;

/**
 * On-demand health checks for processors loaded via {@link ProcessorLoader}, mirroring the legacy
 * {@code ProcessorPool}'s external-service health tracking: {@link #isHealthy(String)} delegates
 * to {@link Processor#isHealthy()} for processors flagged {@code is_external}, and reports
 * internal processors as always healthy without invoking anything.
 */
public final class ProcessorHealthMonitor {

    private final Map<String, Processor> processors;
    private final List<String> externalProcessorIds;

    public ProcessorHealthMonitor(ProcessorLoader.LoadResult loadResult) {
        this.processors = loadResult.getLoaded();
        this.externalProcessorIds = loadResult.getExternalProcessorIds();
    }

    public boolean isHealthy(String processorId) {
        Processor processor = processors.get(processorId);
        if (processor == null) {
            return false;
        }
        if (!externalProcessorIds.contains(processorId)) {
            return true;
        }
        try {
            return processor.isHealthy();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
