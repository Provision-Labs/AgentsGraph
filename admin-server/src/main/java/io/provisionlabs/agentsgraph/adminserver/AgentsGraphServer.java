package io.provisionlabs.agentsgraph.adminserver;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.Map;

/**
 * The runnable AgentsGraph admin-API server - the backend of the
 * <a href="https://github.com/Provision-Labs/agentsgraph-ui">AgentsGraph UI</a>, started straight
 * from this build:
 *
 * <pre>{@code ./gradlew :server:bootRun }</pre>
 *
 * <p><b>Default: pure in-memory mode.</b> The engine bean below overrides the auto-configuration's
 * DataSource-based store beans (every auto-configured bean is {@code @ConditionalOnMissingBean}),
 * and {@link AgentsGraphAutoConfiguration} contributes the {@code /api/agentsgraph/**} REST API
 * around it. On startup two DEBUG
 * flows are executed (one succeeds, one fails at the LLM step), so the UI has graphs, executions
 * AND step traces to show immediately - including a failed step that can be resumed from the UI.
 *
 * <p><b>Database-backed mode.</b> The PostgreSQL driver ships with the server - activate the
 * {@code db} profile ({@code --spring.profiles.active=db}, see {@code application-db.properties})
 * with your {@code spring.datasource.*}: the demo beans below back off automatically (they are
 * conditional on there being no {@code DataSource}), and the auto-configuration wires the three
 * JDBC stores (schemas self-provisioned) over the real database instead.
 */
@SpringBootApplication
public class AgentsGraphServer {

    public static void main(String[] args) {
        SpringApplication.run(AgentsGraphServer.class, args);
    }

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
