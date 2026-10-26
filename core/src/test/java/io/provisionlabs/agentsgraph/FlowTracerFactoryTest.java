package io.provisionlabs.agentsgraph;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.StepTracer;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The host-wide {@link io.provisionlabs.agentsgraph.engine.FlowTracerFactory} observes every run - including
 * {@code resumeFrom}, which takes no per-call listener - and can never break a flow.
 */
class FlowTracerFactoryTest {

    private AgentsGraphEngine engine;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicBoolean llmFails = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        engine = AgentsGraphEngine.inMemory();
        engine.registerProcessor("ocr", (context, step) -> Map.of("json", "{}"));
        engine.registerProcessor("llm", (context, step) -> {
            if (llmFails.get()) {
                throw new IllegalStateException("LLM down");
            }
            return Map.of("summary", "ok");
        });
        NodeDefinition entry = NodeDefinition.builder("entry")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_pipeline")
                .build();
        EdgeDefinition pipeline = EdgeDefinition.builder("edge_pipeline")
                .step(new StepDefinition("s_ocr", "ocr", Map.of()))
                .step(new StepDefinition("s_llm", "llm", Map.of()))
                .build();
        engine.deployGraph(GraphDefinition.builder("doc-flow", "v1")
                .entryNodeId("entry").node(entry).edge(pipeline).build());
        engine.setFlowTracerFactory((graph, initial) -> new StepTracer() {
            @Override
            public void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                      ExecutionContext stepInput, Map<String, Object> rawOutput,
                                      long startedAtMillis, long durationMs) {
                events.add(graph.getId() + ":" + step.getId() + ":ok:" + stepInput.getFlowId().equals(initial.getFlowId()));
            }

            @Override
            public void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                   ExecutionContext stepInput, Throwable failure,
                                   long startedAtMillis, long durationMs) {
                events.add(graph.getId() + ":" + step.getId() + ":failed");
            }
        });
    }

    @Test
    void observesEveryStepOfANormalRun() {
        engine.execute("doc-flow", ExecutionContext.newFlow(Map.of("in", 1), Map.of()));

        assertThat(events).containsExactly("doc-flow:s_ocr:ok:true", "doc-flow:s_llm:ok:true");
    }

    @Test
    void observesAResumedRunToo() {
        llmFails.set(true);
        ExecutionContext initial = ExecutionContext.newFlow(Map.of("in", 1), Map.of());
        assertThatThrownBy(() -> engine.executeDebug("doc-flow", initial));
        assertThat(events).containsExactly("doc-flow:s_ocr:ok:true", "doc-flow:s_llm:failed");

        events.clear();
        llmFails.set(false);
        StepTraceRecord failed = engine.getStepTraces(initial.getFlowId()).get(1);
        engine.resumeFrom(initial.getFlowId(), failed.getSeq());

        assertThat(events).containsExactly("doc-flow:s_llm:ok:true");
    }

    @Test
    void aFailingFactoryLeavesTheFlowRunning() {
        engine.setFlowTracerFactory((graph, initial) -> {
            throw new IllegalStateException("observer broken");
        });

        ExecutionContext result = engine.execute("doc-flow", ExecutionContext.newFlow(Map.of("in", 1), Map.of()));

        assertThat(result.getAccumulatedState()).containsEntry("summary", "ok");
    }

    @Test
    void nullRemovesTheFactory() {
        engine.setFlowTracerFactory(null);

        engine.execute("doc-flow", ExecutionContext.newFlow(Map.of("in", 1), Map.of()));

        assertThat(events).isEmpty();
    }
}
