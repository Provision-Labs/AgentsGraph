package io.provisionlabs.agentsgraph;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.AgentsGraphException;
import io.provisionlabs.agentsgraph.engine.RuntimeOrchestrator;
import io.provisionlabs.agentsgraph.engine.StepTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The live progress hook behind user-facing pipeline status (e.g. a chatbot's status line):
 * {@code execute(graphId, ctx, listener)} delivers started/succeeded/failed per step, in normal
 * AND debug mode, and a broken listener never breaks the flow.
 */
class AgentsGraphEngineProgressListenerTest {

    /** Collects "event:edge/step index/count" strings. */
    private static final class RecordingListener implements StepTracer {
        final List<String> events = new ArrayList<>();

        @Override
        public void stepStarted(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                  int stepCount, ExecutionContext stepInput) {
            events.add("started:" + edge.getId() + "/" + step.getId() + " " + (stepIndex + 1) + "/" + stepCount);
        }

        @Override
        public void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                    ExecutionContext stepInput, Map<String, Object> rawOutput,
                                    long startedAtMillis, long durationMs) {
            events.add("succeeded:" + edge.getId() + "/" + step.getId());
        }

        @Override
        public void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                 ExecutionContext stepInput, Throwable failure,
                                 long startedAtMillis, long durationMs) {
            events.add("failed:" + edge.getId() + "/" + step.getId());
        }
    }

    private AgentsGraphEngine engine;

    @BeforeEach
    void setUp() {
        engine = AgentsGraphEngine.inMemory();
        engine.registerProcessor("ocr", (context, step) -> Map.of("json", "{}"));
        engine.registerProcessor("llm", (context, step) -> Map.of("summary", "S"));

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
    }

    @Test
    void listenerSeesEveryStepInOrderWithPositionAndCount() {
        RecordingListener listener = new RecordingListener();

        engine.execute("doc-flow", ExecutionContext.newFlow(Map.of(), Map.of()), listener);

        assertThat(listener.events).containsExactly(
                "started:edge_pipeline/s_ocr 1/2",
                "succeeded:edge_pipeline/s_ocr",
                "started:edge_pipeline/s_llm 2/2",
                "succeeded:edge_pipeline/s_llm");
    }

    @Test
    void listenerComposesWithDebugModeRecording() {
        RecordingListener listener = new RecordingListener();
        ExecutionContext debug = ExecutionContext.newFlow(Map.of(),
                Map.of(RuntimeOrchestrator.DEBUG_METADATA_KEY, true));

        engine.execute("doc-flow", debug, listener);

        // Both sinks fired: live progress events AND the persisted step trace.
        assertThat(listener.events).hasSize(4);
        assertThat(engine.getStepTraces(debug.getFlowId())).hasSize(2);
    }

    @Test
    void listenerSeesTheFailureAndABrokenListenerNeverBreaksTheFlow() {
        engine.registerProcessor("llm", (context, step) -> {
            throw new IllegalStateException("LLM down");
        });
        RecordingListener recording = new RecordingListener();
        StepTracer broken = new StepTracer() {
            @Override
            public void stepStarted(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                      int stepCount, ExecutionContext stepInput) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                        ExecutionContext stepInput, Map<String, Object> rawOutput,
                                        long startedAtMillis, long durationMs) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                     ExecutionContext stepInput, Throwable failure,
                                     long startedAtMillis, long durationMs) {
                throw new RuntimeException("listener bug");
            }
        };

        // The broken listener alone must not change the outcome: the flow still fails only
        // because of the processor, and a healthy listener still gets the failure event.
        assertThatThrownBy(() -> engine.execute("doc-flow",
                ExecutionContext.newFlow(Map.of(), Map.of()),
                StepTracer.compose(broken, recording)))
                .isInstanceOf(AgentsGraphException.class)
                .hasMessageContaining("s_llm");
        assertThat(recording.events).contains("failed:edge_pipeline/s_llm");

        // And on a healthy graph the broken listener is completely harmless.
        engine.registerProcessor("llm", (context, step) -> Map.of("summary", "S"));
        ExecutionContext ok = engine.execute("doc-flow",
                ExecutionContext.newFlow(Map.of(), Map.of()), StepTracer.compose(broken, StepTracer.NOOP));
        assertThat(ok.getAccumulatedState()).containsKey("summary");
    }
}
