package io.provisionlabs.agentsgraph.spring;

import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;
import io.provisionlabs.agentsgraph.engine.ProcessorLoader;
import io.provisionlabs.agentsgraph.engine.ProcessorRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Processor rows whose classes need live dependencies load through the Spring context: beans by
 * type, by {@code @Qualifier} name when the type is ambiguous, property values by {@code @Value};
 * {@code bean:name} hands over an existing bean; a missing required dependency fails only that row.
 */
class SpringProcessorInstantiatorTest {

    /** Stands in for an HTTP/LLM client - two of them in the context to force a qualifier. */
    public static class Client {
        final String name;

        Client(String name) {
            this.name = name;
        }
    }

    public static class Store {
    }

    /** Loadable from a row: public no-arg constructor, dependencies as annotated fields. */
    public static class WiredProcessor implements Processor {
        @Autowired
        private Store store;
        @Autowired
        @Qualifier("answerClient")
        private Client client;
        @Value("${demo.enabled:true}")
        private boolean enabled = true;
        @Value("${demo.limit:5}")
        private int limit;
        @Autowired(required = false)
        private Runnable optional;
        private Map<String, Object> params;

        @Override
        public void init(Map<String, Object> params) {
            this.params = params;
        }

        @Override
        public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
            return Map.of("client", client.name, "store", store, "enabled", enabled, "limit", limit,
                    "optional", optional == null ? "none" : "set", "params", params);
        }
    }

    /** Needs a bean type the context does not have. */
    public static class Orphan implements Processor {
        @Autowired
        private Thread missing;

        @Override
        public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
            return Map.of();
        }
    }

    /** Two beans of Client, no qualifier - ambiguous on purpose. */
    public static class Ambiguous implements Processor {
        @Autowired
        private Client client;

        @Override
        public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
            return Map.of();
        }
    }

    private GenericApplicationContext context;
    private SpringProcessorInstantiator instantiator;
    private Store store;

    @BeforeEach
    void setUp() {
        System.setProperty("demo.enabled", "false");
        context = new GenericApplicationContext();
        store = new Store();
        context.getBeanFactory().registerSingleton("store", store);
        context.getBeanFactory().registerSingleton("answerClient", new Client("answers"));
        context.getBeanFactory().registerSingleton("editClient", new Client("edits"));
        context.getBeanFactory().registerSingleton("readyProcessor", new WiredProcessor());
        context.refresh();
        instantiator = new SpringProcessorInstantiator(context);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("demo.enabled");
        context.close();
    }

    private static ProcessorDefinition row(String id, String instanceClass) {
        return new ProcessorDefinition(id, id, false, instanceClass, Map.of("k", "v"));
    }

    private static Map<String, Object> run(Processor processor) {
        return processor.execute(ExecutionContext.newFlow(Map.of(), Map.of()), new StepDefinition("s", "p", Map.of()));
    }

    @Test
    void loadsARowInjectingBeansByTypeByQualifierAndPropertiesByValue() {
        ProcessorRegistry registry = new ProcessorRegistry();
        ProcessorLoader.LoadResult result = new ProcessorLoader(registry, instantiator)
                .load(List.of(row("wired", WiredProcessor.class.getName())));

        assertThat(result.getFailures()).isEmpty();
        Map<String, Object> out = run(registry.resolve("wired"));
        assertThat(out).containsEntry("client", "answers")          // @Qualifier picked among two Clients
                .containsEntry("store", store)                        // by type, the only Store
                .containsEntry("enabled", false)                      // @Value from a system property
                .containsEntry("limit", 5)                            // @Value default
                .containsEntry("optional", "none")                    // required = false, no bean: left null
                .containsEntry("params", Map.of("k", "v"));           // init(params) still runs after injection
    }

    @Test
    void beanPrefixHandsOverTheContextBeanItself() throws Exception {
        assertThat(instantiator.instantiate(row("ready", "bean:readyProcessor")))
                .isSameAs(context.getBean("readyProcessor"));
        assertThatThrownBy(() -> instantiator.instantiate(row("x", "bean:noSuchBean")))
                .hasMessageContaining("noSuchBean");
    }

    @Test
    void missingOrAmbiguousDependenciesFailOnlyTheirOwnRows() {
        ProcessorRegistry registry = new ProcessorRegistry();
        ProcessorLoader.LoadResult result = new ProcessorLoader(registry, instantiator).load(List.of(
                row("orphan", Orphan.class.getName()),
                row("ambiguous", Ambiguous.class.getName()),
                row("wired", WiredProcessor.class.getName())));

        assertThat(result.getLoaded()).containsOnlyKeys("wired");
        assertThat(result.getFailures()).extracting(ProcessorLoader.LoadFailure::getProcessorId)
                .containsExactly("orphan", "ambiguous");
        assertThat(result.getFailures().get(0).getReason()).contains("No bean of type java.lang.Thread").contains("Orphan.missing");
        assertThat(result.getFailures().get(1).getReason()).contains("Several beans").contains("answerClient").contains("@Qualifier");
    }

    @Test
    void withoutAContextItBehavesLikeTheReflectiveDefault() throws Exception {
        SpringProcessorInstantiator bare = new SpringProcessorInstantiator();
        assertThat(bare.instantiate(row("orphan", Orphan.class.getName()))).isInstanceOf(Orphan.class);
        assertThatThrownBy(() -> bare.instantiate(row("x", "bean:anything"))).hasMessageContaining("no ApplicationContext");
    }
}
