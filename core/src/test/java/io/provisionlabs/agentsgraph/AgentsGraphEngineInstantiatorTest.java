package io.provisionlabs.agentsgraph;

import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;
import io.provisionlabs.agentsgraph.engine.ProcessorInstantiator;
import io.provisionlabs.agentsgraph.engine.ProcessorLoader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A DI-aware {@link ProcessorInstantiator} on the engine: a processor row whose class needs a
 * dependency loads from the store like any other, the dependency arrives after construction and
 * before {@code init}, and {@link AgentsGraphEngine#reload()} keeps using the configured strategy.
 */
class AgentsGraphEngineInstantiatorTest {

    /** Needs a "service" a DB row cannot express - what programmatic processors used to be for. */
    public static class NeedsAService implements Processor {
        public String service;
        private Map<String, Object> params;

        @Override
        public void init(Map<String, Object> params) {
            if (service == null) {
                throw new IllegalStateException("dependency must be injected before init");
            }
            this.params = params;
        }

        @Override
        public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
            return Map.of("service", service, "params", params);
        }
    }

    @Test
    void engineLoadsRowsThroughTheConfiguredInstantiator() {
        AgentsGraphEngine engine = AgentsGraphEngine.inMemory();
        engine.getProcessorDefinitionStore().put(new ProcessorDefinition(
                "needs-service", "Needs a service", false, NeedsAService.class.getName(), Map.of("retries", 2)));

        // without a DI-aware instantiator the row fails to init (dependency missing) - isolated, not fatal
        ProcessorLoader.LoadResult plain = engine.reload();
        assertThat(plain.getFailures()).extracting(ProcessorLoader.LoadFailure::getProcessorId).containsExactly("needs-service");

        engine.setProcessorInstantiator(ProcessorInstantiator.REFLECTIVE.andThen(instance -> {
            ((NeedsAService) instance).service = "wired-by-container";
            return instance;
        }));
        ProcessorLoader.LoadResult wired = engine.reload();

        assertThat(wired.getFailures()).isEmpty();
        Processor processor = wired.getLoaded().get("needs-service");
        Map<String, Object> out = processor.execute(ExecutionContext.newFlow(Map.of(), Map.of()),
                new StepDefinition("s1", "needs-service", Map.of()));
        assertThat(out).containsEntry("service", "wired-by-container").containsEntry("params", Map.of("retries", 2));

        // null restores the reflective default
        engine.setProcessorInstantiator(null);
        assertThat(engine.getProcessorInstantiator()).isSameAs(ProcessorInstantiator.REFLECTIVE);
    }
}
