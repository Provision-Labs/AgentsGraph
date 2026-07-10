package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.InMemoryConfigStore;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingDelegateConfig;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import io.provisionlabs.agentsgraph.trace.InMemoryTraceStore;
import io.provisionlabs.agentsgraph.trace.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeOrchestratorOutputMappingAndFallbackTest {

    private RuntimeOrchestrator newOrchestrator(InMemoryConfigStore configStore, ProcessorRegistry processorRegistry,
                                                  RoutingDelegateRegistry delegateRegistry, TraceStore traceStore) {
        return new RuntimeOrchestrator(configStore, traceStore, processorRegistry, delegateRegistry);
    }

    @Test
    void delegateOutputIsMappedIntoContextBeforeTheEdgeRuns() {
        InMemoryConfigStore configStore = new InMemoryConfigStore();
        ProcessorRegistry processorRegistry = new ProcessorRegistry();
        RoutingDelegateRegistry delegateRegistry = new RoutingDelegateRegistry();
        TraceStore traceStore = new InMemoryTraceStore();

        // Delegate classifies the document and returns extra fields alongside the edge choice.
        delegateRegistry.register("doc-classifier", (context, config) -> new DelegateResult(
                "edge_ocr", 0.92, Map.of("documentType", "invoice", "confidence", 0.92)));

        processorRegistry.register("read-classification", (context, step) ->
                Map.of("sawDocumentType", context.getAccumulatedState().get("classification.documentType")));

        NodeDefinition classifierNode = NodeDefinition.builder("intent_router")
                .routingStrategy(RoutingStrategy.CLASSIFICATOR)
                .routingDelegate(new RoutingDelegateConfig("model_service", "doc-classifier", Map.of(), 1000))
                .outputMapping("documentType", "classification.documentType")
                .outputMapping("confidence", "classification.confidence")
                .fallbackEdgeId("edge_ocr")
                .build();
        EdgeDefinition ocrEdge = EdgeDefinition.builder("edge_ocr")
                .step(new StepDefinition("s0", "read-classification", Map.of()))
                .build();
        configStore.putGraph(GraphDefinition.builder("g1", "v1")
                .entryNodeId("intent_router").node(classifierNode).edge(ocrEdge).build());

        RuntimeOrchestrator orchestrator = newOrchestrator(configStore, processorRegistry, delegateRegistry, traceStore);
        ExecutionContext result = orchestrator.run("g1", ExecutionContext.newFlow(Map.of(), Map.of()));

        assertThat(result.getAccumulatedState())
                .containsEntry("classification.documentType", "invoice")
                .containsEntry("classification.confidence", 0.92)
                .containsEntry("sawDocumentType", "invoice");
    }

    @Test
    void edgeFailureRoutesToFallbackEdgeInsteadOfAborting() {
        InMemoryConfigStore configStore = new InMemoryConfigStore();
        ProcessorRegistry processorRegistry = new ProcessorRegistry();
        RoutingDelegateRegistry delegateRegistry = new RoutingDelegateRegistry();
        TraceStore traceStore = new InMemoryTraceStore();

        processorRegistry.register("boom", (context, step) -> {
            throw new IllegalStateException("OCR service unavailable");
        });
        processorRegistry.register("error-answer", (context, step) ->
                Map.of("answer", "Sorry, something went wrong: " + context.getAccumulatedState().get("pipeline_error")));

        NodeDefinition entryNode = NodeDefinition.builder("entry")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_ocr")
                .fallbackEdgeId("edge_error_fallback")
                .build();
        EdgeDefinition ocrEdge = EdgeDefinition.builder("edge_ocr")
                .step(new StepDefinition("s0", "boom", Map.of()))
                .build();
        EdgeDefinition fallbackEdge = EdgeDefinition.builder("edge_error_fallback")
                .step(new StepDefinition("s0", "error-answer", Map.of()))
                .build();
        configStore.putGraph(GraphDefinition.builder("g2", "v1")
                .entryNodeId("entry").node(entryNode).edge(ocrEdge).edge(fallbackEdge).build());

        RuntimeOrchestrator orchestrator = newOrchestrator(configStore, processorRegistry, delegateRegistry, traceStore);
        ExecutionContext initialContext = ExecutionContext.newFlow(Map.of(), Map.of());
        ExecutionContext result = orchestrator.run("g2", initialContext);

        // Edge.execute() wraps the processor's exception with step context, so pipeline_error
        // carries that wrapped message rather than the raw "OCR service unavailable" - the point
        // is that it's captured and handed to the fallback edge at all, not swallowed/thrown.
        assertThat(result.getAccumulatedState().get("answer"))
                .asString()
                .startsWith("Sorry, something went wrong: Edge 'edge_ocr' failed at step 's0'");
        // The flow completed (via the fallback edge), it did not propagate the exception.
        assertThat(traceStore.find(initialContext.getFlowId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void edgeFailureWithNoFallbackConfiguredStillPropagates() {
        InMemoryConfigStore configStore = new InMemoryConfigStore();
        ProcessorRegistry processorRegistry = new ProcessorRegistry();
        RoutingDelegateRegistry delegateRegistry = new RoutingDelegateRegistry();
        TraceStore traceStore = new InMemoryTraceStore();

        processorRegistry.register("boom", (context, step) -> {
            throw new IllegalStateException("OCR service unavailable");
        });

        NodeDefinition entryNode = NodeDefinition.builder("entry")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_ocr")
                .build();
        EdgeDefinition ocrEdge = EdgeDefinition.builder("edge_ocr")
                .step(new StepDefinition("s0", "boom", Map.of()))
                .build();
        configStore.putGraph(GraphDefinition.builder("g3", "v1")
                .entryNodeId("entry").node(entryNode).edge(ocrEdge).build());

        RuntimeOrchestrator orchestrator = newOrchestrator(configStore, processorRegistry, delegateRegistry, traceStore);
        ExecutionContext initialContext = ExecutionContext.newFlow(Map.of(), Map.of());

        assertThatThrownBy(() -> orchestrator.run("g3", initialContext))
                .isInstanceOf(AgentsGraphException.class);
        assertThat(traceStore.find(initialContext.getFlowId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.FAILED);
    }
}
