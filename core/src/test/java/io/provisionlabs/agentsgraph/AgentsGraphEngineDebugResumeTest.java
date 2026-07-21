package io.provisionlabs.agentsgraph;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.AgentsGraphException;
import io.provisionlabs.agentsgraph.trace.StepStatus;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The full debug-and-resume workflow through the {@link AgentsGraphEngine} facade: run a flow in
 * debug mode, watch it fail at a step, fix the processor (or the data), and resume from exactly
 * the failed step - earlier steps don't re-run.
 */
class AgentsGraphEngineDebugResumeTest {

    private AgentsGraphEngine engine;
    private final AtomicInteger ocrCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        engine = AgentsGraphEngine.inMemory();

        engine.registerProcessor("ocr", (context, step) -> {
            ocrCalls.incrementAndGet();
            return Map.of("json", "{\"documents\":[1]}");
        });
        engine.registerProcessor("llm", (context, step) ->
                Map.of("summary", "SUMMARY(" + context.getAccumulatedState().get("json") + ")"));

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
    void failedDebugFlowIsResumableFromTheFailedStepAfterFixingTheProcessor() {
        engine.registerProcessor("llm", (context, step) -> {
            throw new IllegalStateException("LLM quota exceeded");
        });

        ExecutionContext initial = ExecutionContext.newFlow(Map.of("file", "doc.pdf"), Map.of());
        assertThatThrownBy(() -> engine.executeDebug("doc-flow", initial))
                .isInstanceOf(AgentsGraphException.class);

        // The failed step is recorded with the exact input it saw.
        List<StepTraceRecord> steps = engine.getStepTraces(initial.getFlowId());
        assertThat(steps).hasSize(2);
        StepTraceRecord failed = steps.get(1);
        assertThat(failed.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(failed.getError()).contains("LLM quota exceeded");
        assertThat(failed.getInputContextJson()).contains("documents");

        // Fix the processor and resume from the failed step: OCR must NOT run again.
        engine.registerProcessor("llm", (context, step) ->
                Map.of("summary", "SUMMARY(" + context.getAccumulatedState().get("json") + ")"));
        int ocrCallsBeforeResume = ocrCalls.get();

        ExecutionContext resumed = engine.resumeFrom(initial.getFlowId(), failed.getSeq());

        assertThat(ocrCalls.get()).isEqualTo(ocrCallsBeforeResume);
        assertThat(resumed.getAccumulatedState().get("summary")).asString().contains("documents");
        // Lineage: the resumed run is a new flow pointing back at the original.
        assertThat(resumed.getMetadata())
                .containsEntry("parent_flow_id", initial.getFlowId())
                .containsEntry("resumed_from_seq", failed.getSeq());
        assertThat(resumed.getFlowId()).isNotEqualTo(initial.getFlowId());
    }

    @Test
    void resumeWithStateOverridesRunsOnTheCorrectedData() {
        ExecutionContext initial = ExecutionContext.newFlow(Map.of("file", "doc.pdf"), Map.of());
        engine.executeDebug("doc-flow", initial);
        StepTraceRecord llmStep = engine.getStepTraces(initial.getFlowId()).get(1);

        // Resume the LLM step with a corrected OCR payload - without re-running OCR.
        ExecutionContext resumed = engine.resumeFrom(initial.getFlowId(), llmStep.getSeq(),
                Map.of("json", "{\"documents\":[\"corrected\"]}"));

        assertThat(resumed.getAccumulatedState().get("summary")).asString().contains("corrected");
    }

    @Test
    void resumingANonDebugFlowExplainsWhatWentWrong() {
        ExecutionContext initial = ExecutionContext.newFlow(Map.of("file", "doc.pdf"), Map.of());
        engine.execute("doc-flow", initial);

        assertThat(engine.getStepTraces(initial.getFlowId())).isEmpty();
        assertThatThrownBy(() -> engine.resumeFrom(initial.getFlowId(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("debug mode");
    }
}
