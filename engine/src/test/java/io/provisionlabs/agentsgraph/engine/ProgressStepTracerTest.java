package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProgressStepTracerTest {

    private record Line(String message, int percent) {}

    @Test
    void reportsMonotonicPercentCappedAt95() {
        List<Line> lines = new ArrayList<>();
        ProgressStepTracer tracer = new ProgressStepTracer(2, (message, percent) -> lines.add(new Line(message, percent)));

        EdgeDefinition edge = EdgeDefinition.builder("edge_a")
                .step(new StepDefinition("s0", "proc-a", Map.of(), List.of(), List.of()))
                .step(new StepDefinition("s1", "proc-b", Map.of(), List.of(), List.of()))
                .build();
        StepDefinition s0 = edge.getSteps().get(0);
        StepDefinition s1 = edge.getSteps().get(1);
        ExecutionContext ctx = ExecutionContext.newFlow(Map.of(), Map.of());

        tracer.stepStarted("n", edge, s0, 0, 2, ctx);
        tracer.stepSucceeded("n", edge, s0, 0, ctx, Map.of(), 0, 42);
        tracer.stepStarted("n", edge, s1, 1, 2, ctx);
        tracer.stepSucceeded("n", edge, s1, 1, ctx, Map.of(), 0, 7);

        assertThat(lines).extracting(Line::percent).containsExactly(0, 50, 50, 95);
        assertThat(lines.get(0).message()).contains("edge_a").contains("proc-a").contains("(1/2)");
        assertThat(lines.get(1).message()).contains("42 ms");
    }

    @Test
    void failureReportsWithoutAdvancingTheCounter() {
        List<Line> lines = new ArrayList<>();
        ProgressStepTracer tracer = new ProgressStepTracer(4, (message, percent) -> lines.add(new Line(message, percent)));

        EdgeDefinition edge = EdgeDefinition.builder("edge_a")
                .step(new StepDefinition("s0", "proc-a", Map.of(), List.of(), List.of()))
                .build();
        StepDefinition s0 = edge.getSteps().get(0);
        ExecutionContext ctx = ExecutionContext.newFlow(Map.of(), Map.of());

        tracer.stepFailed("n", edge, s0, 0, ctx, new IllegalStateException("boom"), 0, 5);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).message()).contains("failed").contains("boom");
        assertThat(lines.get(0).percent()).isZero();
    }

    @Test
    void forGraphUsesTheGraphsTotalStepCount() {
        GraphDefinition graph = GraphDefinition.builder("g", "v1")
                .entryNodeId("n")
                .node(NodeDefinition.builder("n").routingStrategy(RoutingStrategy.RULES).routingRule("default", "e1").build())
                .edge(EdgeDefinition.builder("e1")
                        .step(new StepDefinition("s0", "p0", Map.of(), List.of(), List.of()))
                        .step(new StepDefinition("s1", "p1", Map.of(), List.of(), List.of()))
                        .build())
                .edge(EdgeDefinition.builder("e2")
                        .step(new StepDefinition("s2", "p2", Map.of(), List.of(), List.of()))
                        .build())
                .build();

        assertThat(graph.totalStepCount()).isEqualTo(3);

        List<Line> lines = new ArrayList<>();
        ProgressStepTracer tracer = ProgressStepTracer.forGraph(graph, (message, percent) -> lines.add(new Line(message, percent)));
        EdgeDefinition edge = graph.getEdge("e1");
        ExecutionContext ctx = ExecutionContext.newFlow(Map.of(), Map.of());
        tracer.stepSucceeded("n", edge, edge.getSteps().get(0), 0, ctx, Map.of(), 0, 1);

        assertThat(lines.get(0).percent()).isEqualTo(33); // 1 из 3
    }

    @Test
    void executionContextRequireFailsLoudlyOnMissingKey() {
        ExecutionContext ctx = ExecutionContext.newFlow(Map.of("text", "hi"), Map.of())
                .withMergedState(Map.of("json", "{\"a\":1}"));

        assertThat(ctx.require("json")).isEqualTo("{\"a\":1}");
        assertThat(ctx.require("text")).isEqualTo("hi"); // fallback на input data
        assertThat(ctx.requireString("json")).isEqualTo("{\"a\":1}");

        assertThatThrownBy(() -> ctx.require("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing")
                .hasMessageContaining("json")   // перечень доступных ключей в сообщении
                .hasMessageContaining("text");
    }
}
