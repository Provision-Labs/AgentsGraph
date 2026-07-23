package io.provisionlabs.agentsgraph;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.AgentsGraphException;
import io.provisionlabs.agentsgraph.trace.StepTraceJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code dumpStepTraces} (portable JSON dump) and {@code describeFlow} (ops-facing text report). */
class AgentsGraphEngineFlowInspectionTest {

    private AgentsGraphEngine engine;

    @BeforeEach
    void setUp() {
        engine = AgentsGraphEngine.inMemory();
        engine.registerProcessor("ocr", (context, step) -> Map.of("json", "{\"documents\":[]}"));
        engine.registerProcessor("llm", (context, step) -> Map.of("summary", "SUMMARY"));

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
    void dumpStepTracesProducesASelfContainedParseableDocument() {
        ExecutionContext initial = ExecutionContext.newFlow(Map.of("file", "doc.pdf"), Map.of());
        engine.executeDebug("doc-flow", initial);

        String dump = engine.dumpStepTraces(initial.getFlowId());

        assertThat(StepTraceJson.fromJson(dump)).hasSize(2);
        assertThat(dump).contains("\"processorRef\" : \"ocr\"").contains("\"processorRef\" : \"llm\"");
        assertThat(dump).contains("documents");
    }

    @Test
    void describeFlowReportsStatusAndEveryStep() {
        engine.registerProcessor("llm", (context, step) -> {
            throw new IllegalStateException("LLM quota exceeded");
        });
        ExecutionContext initial = ExecutionContext.newFlow(Map.of("file", "doc.pdf"), Map.of());
        assertThatThrownBy(() -> engine.executeDebug("doc-flow", initial))
                .isInstanceOf(AgentsGraphException.class);

        String report = engine.describeFlow(initial.getFlowId());

        assertThat(report)
                .contains("status FAILED")
                .contains("s_ocr")
                .contains("s_llm")
                .contains("FAILED")
                .contains("LLM quota exceeded");
    }

    @Test
    void describeFlowExplainsWhenTheFlowWasNotRunInDebugMode() {
        ExecutionContext initial = ExecutionContext.newFlow(Map.of("file", "doc.pdf"), Map.of());
        engine.execute("doc-flow", initial);

        String report = engine.describeFlow(initial.getFlowId());

        assertThat(report).contains("status COMPLETED").contains("not run in debug mode");
    }
}
