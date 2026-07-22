package io.provisionlabs.agentsgraph.demo;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * The README's minimal AgentsGraph admin-API server, runnable as-is - pure in-memory mode: the
 * engine below overrides the starter's DataSource-based store beans (every starter bean is
 * {@code @ConditionalOnMissingBean}), and the starter contributes the {@code /api/agentsgraph/**}
 * REST API around it. On startup two debug flows are executed (one succeeds, one fails at the
 * LLM step), so the AgentsGraph UI has graphs, executions AND step traces to show immediately -
 * including a failed step that can be resumed from the UI.
 *
 * <p>For the database-backed mode, delete the {@code agentsGraphEngine}/{@code demoFlows} beans,
 * add {@code spring-boot-starter-jdbc} + a driver, and set {@code spring.datasource.*} - see
 * {@code application.properties}.
 */
@SpringBootApplication
public class AgentsGraphServer {

    public static void main(String[] args) {
        SpringApplication.run(AgentsGraphServer.class, args);
    }

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
        };
    }
}
