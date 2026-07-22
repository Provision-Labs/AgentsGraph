package io.provisionlabs.agentsgraph.web;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.web.dto.ExecutionDto;
import io.provisionlabs.agentsgraph.web.dto.ProcessorDto;
import io.provisionlabs.agentsgraph.web.dto.ResumeResultDto;
import io.provisionlabs.agentsgraph.web.dto.StepDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The UI's read-model over a live engine: graph list, graph-scoped processors (DB rows +
 * programmatic refs), execution traces, debug step traces with PARSED in/out, and resume.
 */
class AgentsGraphAdminServiceTest {

    private AgentsGraphEngine engine;
    private AgentsGraphAdminService service;

    @BeforeEach
    void setUp() {
        engine = AgentsGraphEngine.inMemory();
        service = new AgentsGraphAdminService(engine);

        // "ocr" has a DB row; "llm" is programmatic-only (registered in code below).
        engine.getProcessorDefinitionStore().put(new ProcessorDefinition(
                "ocr", "OCR service", true, "com.example.OcrProcessor",
                Map.of("processUrl", "http://ocr/process")));
        engine.registerProcessor("ocr", (context, step) -> Map.of("json", "{\"documents\":[]}"));
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
    void listsGraphsWithTopologySummary() {
        assertThat(service.listGraphs())
                .singleElement()
                .satisfies(graph -> {
                    assertThat(graph.getId()).isEqualTo("doc-flow");
                    assertThat(graph.getVersion()).isEqualTo("v1");
                    assertThat(graph.getEntryNodeId()).isEqualTo("entry");
                    assertThat(graph.getNodeCount()).isEqualTo(1);
                    assertThat(graph.getEdgeCount()).isEqualTo(1);
                });
        assertThat(service.getGraphJson("doc-flow")).contains("\"id\":\"doc-flow\"");
    }

    @Test
    void graphProcessorsResolveDbRowsAndFlagProgrammaticRefs() {
        List<ProcessorDto> processors = service.listGraphProcessors("doc-flow");

        assertThat(processors).extracting(ProcessorDto::getId).containsExactly("ocr", "llm");
        ProcessorDto ocr = processors.get(0);
        assertThat(ocr.isProgrammatic()).isFalse();
        assertThat(ocr.getInstanceClass()).isEqualTo("com.example.OcrProcessor");
        assertThat(ocr.getParams()).containsEntry("processUrl", "http://ocr/process");
        ProcessorDto llm = processors.get(1);
        assertThat(llm.isProgrammatic()).isTrue();
        assertThat(llm.getInstanceClass()).isNull();
    }

    @Test
    void listsExecutionsAndFiltersByStatus() {
        ExecutionContext ok = ExecutionContext.newFlow(Map.of("file", "a.pdf"), Map.of());
        engine.execute("doc-flow", ok);
        engine.registerProcessor("llm", (context, step) -> {
            throw new IllegalStateException("LLM down");
        });
        ExecutionContext broken = ExecutionContext.newFlow(Map.of("file", "b.pdf"), Map.of());
        catchThrowable(() -> engine.execute("doc-flow", broken));

        assertThat(service.listExecutions(null, null)).hasSize(2);
        List<ExecutionDto> failed = service.listExecutions("failed", null);
        assertThat(failed).singleElement().satisfies(execution -> {
            assertThat(execution.getFlowId()).isEqualTo(broken.getFlowId());
            assertThat(execution.getError()).contains("LLM down");
        });
        assertThat(service.getExecution(ok.getFlowId()).getStatus()).isEqualTo("COMPLETED");
        assertThat(service.getExecutionReport(ok.getFlowId())).contains("status COMPLETED");
    }

    @Test
    void debugStepsExposeParsedInputAndOutput() {
        ExecutionContext debug = ExecutionContext.newFlow(Map.of("file", "doc.pdf"), Map.of());
        engine.executeDebug("doc-flow", debug);

        List<StepDto> steps = service.listSteps(debug.getFlowId());
        assertThat(steps).hasSize(2);

        StepDto llmStep = service.getStep(debug.getFlowId(), 1);
        assertThat(llmStep.getProcessorRef()).isEqualTo("llm");
        assertThat(llmStep.isRestartable()).isTrue();
        // Parsed structures, not escaped strings: the UI renders JSON trees directly.
        assertThat(llmStep.getInputContext()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) llmStep.getInputContext();
        assertThat((Map<String, Object>) input.get("accumulated_state")).containsKey("json");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) llmStep.getOutput();
        assertThat(output).containsKey("summary");
    }

    @Test
    void resumeReRunsFromTheStepAndReportsTheNewFlow() {
        ExecutionContext debug = ExecutionContext.newFlow(Map.of("file", "doc.pdf"), Map.of());
        engine.executeDebug("doc-flow", debug);

        ResumeResultDto result = service.resume(debug.getFlowId(), 1,
                Map.of("json", "{\"documents\":[\"corrected\"]}"));

        assertThat(result.getError()).isNull();
        assertThat(result.getFlowId()).isNotNull().isNotEqualTo(debug.getFlowId());
        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        // The resumed flow ran on the corrected data and is itself inspectable.
        assertThat(service.getExecution(result.getFlowId()).getStatus()).isEqualTo("COMPLETED");
        assertThat(service.listSteps(result.getFlowId())).isNotEmpty();
    }

    @Test
    void resumingAnUnknownStepIs404Material() {
        assertThatThrownBy(() -> service.resume("no-such-flow", 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
