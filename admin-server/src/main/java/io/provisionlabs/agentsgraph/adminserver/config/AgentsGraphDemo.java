package io.provisionlabs.agentsgraph.adminserver.config;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.config.*;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
public class AgentsGraphDemo {

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
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
    @ConditionalOnMissingBean(DataSource.class)
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
