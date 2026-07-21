package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.InMemoryConfigStore;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import io.provisionlabs.agentsgraph.trace.InMemoryTraceStore;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Debug-mode step tracing in the orchestrator: with the {@link RuntimeOrchestrator#DEBUG_METADATA_KEY}
 * metadata flag set, every step's input context and raw output are recorded into the
 * {@link InMemoryTraceStore}'s step-level trace - and {@link RuntimeOrchestrator#resume}
 * re-enters the graph at a recorded step.
 */
class RuntimeOrchestratorStepTraceTest {

    private InMemoryConfigStore configStore;
    private ProcessorRegistry processorRegistry;
    private InMemoryTraceStore traceStore;
    private RuntimeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        configStore = new InMemoryConfigStore();
        processorRegistry = new ProcessorRegistry();
        traceStore = new InMemoryTraceStore();
        orchestrator = new RuntimeOrchestrator(configStore, traceStore, processorRegistry,
                new RoutingDelegateRegistry());

        // Two-step edge: s0 uppercases the text, s1 wraps s0's output.
        processorRegistry.register("uppercase", (context, step) ->
                Map.of("upper", String.valueOf(context.getInputData().get("text")).toUpperCase()));
        processorRegistry.register("wrap", (context, step) ->
                Map.of("wrapped", "[" + context.getAccumulatedState().get("upper") + "]"));

        NodeDefinition entry = NodeDefinition.builder("entry")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_main")
                .build();
        EdgeDefinition mainEdge = EdgeDefinition.builder("edge_main")
                .step(new StepDefinition("s0", "uppercase", Map.of()))
                .step(new StepDefinition("s1", "wrap", Map.of()))
                .build();
        configStore.putGraph(GraphDefinition.builder("g1", "v1")
                .entryNodeId("entry").node(entry).edge(mainEdge).build());
    }

    private static ExecutionContext debugContext(Map<String, Object> input) {
        return ExecutionContext.newFlow(input, Map.of(RuntimeOrchestrator.DEBUG_METADATA_KEY, true));
    }

    @Test
    void debugRunRecordsEveryStepWithInputAndRawOutput() {
        ExecutionContext initial = debugContext(Map.of("text", "hello"));
        orchestrator.run("g1", initial);

        List<StepTraceRecord> steps = traceStore.findSteps(initial.getFlowId());
        assertThat(steps).hasSize(2);

        StepTraceRecord first = steps.get(0);
        assertThat(first.getSeq()).isZero();
        assertThat(first.getNodeId()).isEqualTo("entry");
        assertThat(first.getEdgeId()).isEqualTo("edge_main");
        assertThat(first.getStepId()).isEqualTo("s0");
        assertThat(first.getStepIndex()).isZero();
        assertThat(first.getProcessorRef()).isEqualTo("uppercase");
        assertThat(first.getGraphId()).isEqualTo("g1");
        assertThat(first.getGraphVersion()).isEqualTo("v1");
        assertThat(first.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(first.isRestartable()).isTrue();
        assertThat(first.getInputContextJson()).contains("\"text\":\"hello\"");
        assertThat(first.getOutputJson()).contains("\"upper\":\"HELLO\"");

        StepTraceRecord second = steps.get(1);
        assertThat(second.getSeq()).isEqualTo(1);
        assertThat(second.getStepId()).isEqualTo("s1");
        // s1's recorded INPUT contains s0's forwarded output - the exact data it saw.
        assertThat(second.getInputContextJson()).contains("\"upper\":\"HELLO\"");
        assertThat(second.getOutputJson()).contains("\"wrapped\":\"[HELLO]\"");
    }

    @Test
    void nonDebugRunRecordsNothing() {
        ExecutionContext initial = ExecutionContext.newFlow(Map.of("text", "hello"), Map.of());
        orchestrator.run("g1", initial);

        assertThat(traceStore.findSteps(initial.getFlowId())).isEmpty();
    }

    @Test
    void failedStepIsRecordedWithItsInputAndStackTrace() {
        processorRegistry.register("wrap", (context, step) -> {
            throw new IllegalStateException("wrap exploded");
        });
        ExecutionContext initial = debugContext(Map.of("text", "hello"));

        assertThatThrownBy(() -> orchestrator.run("g1", initial))
                .isInstanceOf(AgentsGraphException.class);

        List<StepTraceRecord> steps = traceStore.findSteps(initial.getFlowId());
        assertThat(steps).hasSize(2);
        StepTraceRecord failed = steps.get(1);
        assertThat(failed.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(failed.getError()).contains("wrap exploded");
        // The failing step's exact input is captured - the failure is reproducible as-is.
        assertThat(failed.getInputContextJson()).contains("\"upper\":\"HELLO\"");
    }

    @Test
    void resumeReentersTheEdgeAtTheGivenStepOnly() {
        ExecutionContext original = debugContext(Map.of("text", "hello"));
        orchestrator.run("g1", original);

        int[] uppercaseCalls = {0};
        processorRegistry.register("uppercase", (context, step) -> {
            uppercaseCalls[0]++;
            return Map.of("upper", "SHOULD NOT RUN");
        });

        // Restore what step s1 saw and resume from it: s0 must not run again.
        ExecutionContext restored = ExecutionContext
                .newFlow(Map.of("text", "hello"), Map.of(RuntimeOrchestrator.DEBUG_METADATA_KEY, true))
                .withMergedState(Map.of("upper", "HELLO"));
        ExecutionContext result = orchestrator.resume("g1", restored, "entry", "edge_main", 1);

        assertThat(uppercaseCalls[0]).isZero();
        assertThat(result.getAccumulatedState()).containsEntry("wrapped", "[HELLO]");
        // The resumed run is itself step-traced, starting at the resumed step.
        List<StepTraceRecord> resumedSteps = traceStore.findSteps(restored.getFlowId());
        assertThat(resumedSteps).hasSize(1);
        assertThat(resumedSteps.get(0).getStepId()).isEqualTo("s1");
        assertThat(resumedSteps.get(0).getStepIndex()).isEqualTo(1);
    }
}
