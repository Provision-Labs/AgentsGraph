package io.provisionlabs.agentsgraph;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import io.provisionlabs.agentsgraph.trace.TraceRecord;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeOrchestratorTest {

    @Test
    void routesByRulesAndExecutesEdgePipeline() {
        AgentsGraphEngine engine = AgentsGraphEngine.inMemory();

        engine.registerProcessor("uppercase", (context, step) -> {
            String text = String.valueOf(context.getInputData().get("message"));
            return Map.of("uppercased", text.toUpperCase());
        });

        NodeDefinition intentRouter = NodeDefinition.builder("intent_router")
                .type("router")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("intent==billing", "edge_billing")
                .routingRule("default", "edge_support")
                .fallbackEdgeId("edge_support")
                .build();

        EdgeDefinition billingEdge = EdgeDefinition.builder("edge_billing")
                .step(new StepDefinition("uppercase_step", "uppercase", Map.of()))
                .outputMapping("uppercased", "accumulated_state.reply")
                .tagToAdd("billing")
                .build();

        GraphDefinition graph = GraphDefinition.builder("support_flow", "v1")
                .entryNodeId("intent_router")
                .node(intentRouter)
                .edge(billingEdge)
                .build();

        engine.deployGraph(graph);

        Map<String, Object> input = new HashMap<>();
        input.put("intent", "billing");
        input.put("message", "hello");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenant_id", "acme");

        ExecutionContext initialContext = ExecutionContext.newFlow(input, metadata);
        ExecutionContext result = engine.execute("support_flow", initialContext);

        assertThat(result.getAccumulatedState()).containsEntry("accumulated_state.reply", "HELLO");

        TraceRecord trace = engine.getTraceStore().find(initialContext.getFlowId()).orElseThrow();
        assertThat(trace.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(trace.getTags()).contains("billing");
        assertThat(trace.getAuditLog()).hasSize(1);
        assertThat(trace.getAuditLog().get(0).getNodeId()).isEqualTo("intent_router");
    }
}
