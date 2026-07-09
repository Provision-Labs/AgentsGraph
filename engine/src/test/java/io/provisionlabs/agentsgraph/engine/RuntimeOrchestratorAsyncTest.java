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
import io.provisionlabs.agentsgraph.trace.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeOrchestratorAsyncTest {

    private RuntimeOrchestrator buildOrchestrator(TraceStore traceStore, OutputSink outputSink, Executor executor) {
        InMemoryConfigStore configStore = new InMemoryConfigStore();
        ProcessorRegistry processorRegistry = new ProcessorRegistry();
        processorRegistry.register("uppercase", (context, step) ->
                Map.of("out", String.valueOf(context.getInputData().get("message")).toUpperCase()));

        NodeDefinition node = NodeDefinition.builder("entry")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_main")
                .fallbackEdgeId("edge_main")
                .build();
        EdgeDefinition edge = EdgeDefinition.builder("edge_main")
                .step(new StepDefinition("s0", "uppercase", Map.of()))
                .outputMapping("out", "reply")
                .build();
        GraphDefinition graph = GraphDefinition.builder("g1", "v1")
                .entryNodeId("entry")
                .node(node)
                .edge(edge)
                .build();
        configStore.putGraph(graph);

        return new RuntimeOrchestrator(
                configStore, traceStore, processorRegistry, new RoutingDelegateRegistry(), outputSink, executor);
    }

    @Test
    void runAsyncCompletesWithTheSameResultAsRun() throws ExecutionException, InterruptedException, TimeoutException {
        TraceStore traceStore = new InMemoryTraceStore();
        RuntimeOrchestrator orchestrator = buildOrchestrator(traceStore, NoopOutputSink.INSTANCE,
                Executors.newSingleThreadExecutor());

        ExecutionContext initialContext = ExecutionContext.newFlow(Map.of("message", "hi"), Map.of());
        CompletableFuture<ExecutionContext> future = orchestrator.runAsync("g1", initialContext);

        ExecutionContext result = future.get(5, TimeUnit.SECONDS);

        assertThat(result.getAccumulatedState()).containsEntry("reply", "HI");
        assertThat(traceStore.find(initialContext.getFlowId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void runAsyncRunsOnTheGivenExecutorNotTheCallingThread() throws Exception {
        TraceStore traceStore = new InMemoryTraceStore();
        String[] executingThreadName = new String[1];
        Executor trackingExecutor = runnable -> {
            Thread t = new Thread(() -> {
                executingThreadName[0] = Thread.currentThread().getName();
                runnable.run();
            }, "tracking-executor-thread");
            t.start();
        };

        RuntimeOrchestrator orchestrator = buildOrchestrator(traceStore, NoopOutputSink.INSTANCE, trackingExecutor);
        ExecutionContext initialContext = ExecutionContext.newFlow(Map.of("message", "hi"), Map.of());

        orchestrator.runAsync("g1", initialContext).get(5, TimeUnit.SECONDS);

        assertThat(executingThreadName[0]).isEqualTo("tracking-executor-thread");
        assertThat(executingThreadName[0]).isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void savedOutputsReachTheConfiguredOutputSink() throws Exception {
        InMemoryOutputSink outputSink = new InMemoryOutputSink();
        TraceStore traceStore = new InMemoryTraceStore();

        InMemoryConfigStore configStore = new InMemoryConfigStore();
        ProcessorRegistry processorRegistry = new ProcessorRegistry();
        processorRegistry.register("extract", (context, step) -> Map.of("tables", "T1", "junk", "ignored"));

        NodeDefinition node = NodeDefinition.builder("entry")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_main")
                .fallbackEdgeId("edge_main")
                .build();
        EdgeDefinition edge = EdgeDefinition.builder("edge_main")
                .step(new StepDefinition("s0", "extract", Map.of(), java.util.List.of(), java.util.List.of("tables")))
                .build();
        configStore.putGraph(GraphDefinition.builder("g2", "v1")
                .entryNodeId("entry").node(node).edge(edge).build());

        RuntimeOrchestrator orchestrator = new RuntimeOrchestrator(
                configStore, traceStore, processorRegistry, new RoutingDelegateRegistry(),
                outputSink, Executors.newSingleThreadExecutor());

        ExecutionContext initialContext = ExecutionContext.newFlow(Map.of(), Map.of());
        orchestrator.run("g2", initialContext);

        assertThat(outputSink.getSaved(initialContext.getFlowId())).hasSize(1);
        assertThat(outputSink.getSaved(initialContext.getFlowId()).get(0)).containsExactly(Map.entry("tables", "T1"));
    }
}
