package io.provisionlabs.agentsgraph.adminserver.app;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * The pure in-memory demo mode of the admin server, active ONLY under the {@code inmemory}
 * profile (the default - see {@code spring.profiles.default} in {@code application.properties};
 * running with {@code --spring.profiles.active=db} switches it off and the auto-configuration
 * wires the JDBC stores over the real database instead).
 *
 * <p>Why a profile and not {@code @ConditionalOnMissingBean(DataSource.class)}: bean conditions
 * are only reliable inside AUTO-configuration classes. This is a plain user {@code @Configuration},
 * and user configurations are processed BEFORE auto-configurations - at that moment the
 * DataSource bean (which {@code DataSourceAutoConfiguration} contributes later) does not exist
 * yet, so the condition always passed and the demo beans leaked into the {@code db} mode.
 */
@Configuration
@Profile("inmemory")
public class AgentsGraphDemo {

    private static final Logger log = LoggerFactory.getLogger(AgentsGraphDemo.class);

    @Bean
    public AgentsGraphEngine agentsGraphEngine() {
        AgentsGraphEngine engine = AgentsGraphEngine.inMemory();

        engine.registerProcessor("prepare", (context, step) ->
                Map.of("prompt", "Summarize: " + context.getInputData().get("text")));
        engine.registerProcessor("llm", (context, step) -> {
            if (Boolean.TRUE.equals(context.getInputData().get("failLlm"))) {
                throw new IllegalStateException("LLM service unavailable (demo failure)");
            }
            return Map.of("summary", "SUMMARY(" + context.getAccumulatedState().get("prompt") + ")");
        });

        NodeDefinition entry = NodeDefinition.builder("entry")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_pipeline")
                .build();
        EdgeDefinition pipeline = EdgeDefinition.builder("edge_pipeline")
                .step(new StepDefinition("s_prepare", "prepare", Map.of()))
                .step(new StepDefinition("s_llm", "llm", Map.of()))
                .build();
        engine.deployGraph(GraphDefinition.builder("demo", "v1")
                .entryNodeId("entry").node(entry).edge(pipeline).build());
        return engine;
    }

    /** Seeds the trace stores with one successful and one failed DEBUG run for the UI to show. */
    @Bean
    public CommandLineRunner demoFlows(AgentsGraphEngine engine) {
        return args -> {
            log.info("Seeding demo flows: one successful and one INTENTIONALLY failing run"
                    + " (the ERROR + stack trace below is the demo failure, not a problem)");
            engine.executeDebug("demo",
                    ExecutionContext.newFlow(Map.of("text", "hello agentsgraph"), Map.of()));
            try {
                engine.executeDebug("demo",
                        ExecutionContext.newFlow(Map.of("text", "broken run", "failLlm", true), Map.of()));
            } catch (RuntimeException expected) {
                // The failed flow is now in the trace stores (status FAILED, step trace with the
                // exact input + stack trace) - resume it from the UI or via
                // POST /api/agentsgraph/executions/{flowId}/steps/{seq}/resume.
            }
            log.info("Demo flows seeded - open the UI or GET /api/agentsgraph/executions");
        };
    }
}
