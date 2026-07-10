package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.ConfigStore;
import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ExecutionEvent;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import io.provisionlabs.agentsgraph.trace.RoutingOutcome;
import io.provisionlabs.agentsgraph.trace.TraceStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Layer 3 - Runtime Orchestrator: the core execution loop mapping the conceptual Agent phases
 * (Observe -&gt; Plan -&gt; Act -&gt; Reflect) onto {@link Node} and {@link Edge} evaluation, walking a
 * {@link GraphDefinition} from its entry node until an {@link EdgeDefinition} with no
 * {@code nextNodeId} is reached, recording every hop into the {@link TraceStore} and forwarding
 * each edge's saved outputs to an {@link OutputSink}.
 *
 * <p>Supports both synchronous ({@link #run}) and asynchronous ({@link #runAsync}) execution of a
 * flow, mirroring the legacy docscan-pipeline's {@code process}/{@code processAsync} split - the
 * flow's live status is always visible through the {@link TraceStore} regardless of which one is
 * used.
 *
 * <p>Two behaviours a {@code Node}'s {@code fallback_edge_id}/{@code output_mapping} enable:
 * <ul>
 *   <li>when a {@link io.provisionlabs.agentsgraph.config.RoutingStrategy#CLASSIFICATOR} node's delegate produces a decision, its raw
 *       {@link DelegateResult#getRaw()} is projected through the node's {@code output_mapping}
 *       and merged into the context <em>before</em> the selected edge runs, so e.g. a document
 *       classifier's result is visible to that edge's steps - not just used to pick the edge.</li>
 *   <li>if the selected edge's step pipeline itself throws (as opposed to routing failing to
 *       produce a decision, which {@code fallback_edge_id} already covered), the node's
 *       {@code fallback_edge_id} - if set - is run instead of aborting the whole flow, with the
 *       failure's message merged into the context under {@code pipeline_error}. This lets a graph
 *       shape its own user-facing error response instead of every caller having to catch
 *       exceptions from {@link #run}/{@link #runAsync}.</li>
 * </ul>
 */
public final class RuntimeOrchestrator {

    private final ConfigStore configStore;
    private final TraceStore traceStore;
    private final ProcessorRegistry processorRegistry;
    private final RoutingDelegateRegistry delegateRegistry;
    private final OutputSink outputSink;
    private final Executor asyncExecutor;
    private final ConditionEngine conditionEngine = new ConditionEngine();

    public RuntimeOrchestrator(ConfigStore configStore, TraceStore traceStore,
                                ProcessorRegistry processorRegistry, RoutingDelegateRegistry delegateRegistry) {
        this(configStore, traceStore, processorRegistry, delegateRegistry,
                NoopOutputSink.INSTANCE, ForkJoinPool.commonPool());
    }

    public RuntimeOrchestrator(ConfigStore configStore, TraceStore traceStore,
                                ProcessorRegistry processorRegistry, RoutingDelegateRegistry delegateRegistry,
                                OutputSink outputSink, Executor asyncExecutor) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.traceStore = Objects.requireNonNull(traceStore, "traceStore");
        this.processorRegistry = Objects.requireNonNull(processorRegistry, "processorRegistry");
        this.delegateRegistry = Objects.requireNonNull(delegateRegistry, "delegateRegistry");
        this.outputSink = Objects.requireNonNull(outputSink, "outputSink");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
    }

    /** Runs {@code graphId} from its entry node to completion, returning the final context snapshot. */
    public ExecutionContext run(String graphId, ExecutionContext initialContext) {
        GraphDefinition graph = configStore.getGraph(graphId);
        String tenantId = String.valueOf(initialContext.getMetadata().get("tenant_id"));
        traceStore.startFlow(initialContext.getFlowId(), tenantId);

        ExecutionContext context = initialContext;
        String currentNodeId = graph.getEntryNodeId();

        try {
            while (currentNodeId != null) {
                NodeDefinition nodeDefinition = graph.getNode(currentNodeId);
                Node node = new Node(nodeDefinition, conditionEngine, delegateRegistry);

                // Plan: evaluate the node's routing table/delegate against the current context.
                RoutingDecision decision = node.route(context);
                context = applyOutputMapping(context, nodeDefinition, decision);

                EdgeDefinition edgeDefinition = graph.getEdge(decision.getNextEdgeId());
                Edge edge = new Edge(edgeDefinition, processorRegistry);

                // Act: run the selected edge's pipeline, falling back to fallback_edge_id (if set)
                // rather than aborting the flow when the edge's own step pipeline throws.
                EdgeResult edgeResult;
                try {
                    edgeResult = edge.execute(context);
                } catch (RuntimeException stepFailure) {
                    String fallbackEdgeId = nodeDefinition.getFallbackEdgeId();
                    if (fallbackEdgeId == null || fallbackEdgeId.equals(edgeDefinition.getId())) {
                        throw stepFailure;
                    }
                    edgeDefinition = graph.getEdge(fallbackEdgeId);
                    edge = new Edge(edgeDefinition, processorRegistry);
                    context = context.withMergedState(Map.of("pipeline_error", String.valueOf(stepFailure.getMessage())));
                    decision = new RoutingDecision(fallbackEdgeId, 0.0, RoutingSource.FALLBACK);
                    edgeResult = edge.execute(context);
                }
                context = edgeResult.getUpdatedContext();

                // Reflect: persist the routing decision, resulting snapshot, tags and saved outputs.
                RoutingOutcome outcome = new RoutingOutcome(
                        decision.getNextEdgeId(), decision.getConfidence(), decision.getSource().name());
                traceStore.appendEvent(initialContext.getFlowId(),
                        new ExecutionEvent(nodeDefinition.getId(), outcome, Instant.now(), context));
                if (!edgeResult.getTagsAdded().isEmpty()) {
                    traceStore.addTags(initialContext.getFlowId(), edgeResult.getTagsAdded());
                }
                outputSink.save(initialContext.getFlowId(), edgeResult.getSavedOutputs());

                currentNodeId = edgeDefinition.getNextNodeId();
            }
            traceStore.updateStatus(initialContext.getFlowId(), ExecutionStatus.COMPLETED);
            return context;
        } catch (RuntimeException e) {
            traceStore.updateStatus(initialContext.getFlowId(), ExecutionStatus.FAILED);
            throw e;
        }
    }

    /** Asynchronous counterpart of {@link #run}, executed on this orchestrator's configured executor. */
    public CompletableFuture<ExecutionContext> runAsync(String graphId, ExecutionContext initialContext) {
        return runAsync(graphId, initialContext, asyncExecutor);
    }

    /** Asynchronous counterpart of {@link #run}, executed on a caller-supplied executor. */
    public CompletableFuture<ExecutionContext> runAsync(String graphId, ExecutionContext initialContext,
                                                          Executor executor) {
        return CompletableFuture.supplyAsync(() -> run(graphId, initialContext), executor);
    }

    /**
     * Projects {@link RoutingDecision#getDelegateOutput()} through the node's {@code
     * output_mapping} and merges it into the context; a no-op for {@code RULES}/{@code FALLBACK}
     * decisions (empty {@code delegateOutput}) or nodes with no {@code output_mapping}.
     */
    private static ExecutionContext applyOutputMapping(ExecutionContext context, NodeDefinition nodeDefinition,
                                                         RoutingDecision decision) {
        if (decision.getDelegateOutput().isEmpty() || nodeDefinition.getOutputMapping().isEmpty()) {
            return context;
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : nodeDefinition.getOutputMapping().entrySet()) {
            if (decision.getDelegateOutput().containsKey(mapping.getKey())) {
                mapped.put(mapping.getValue(), decision.getDelegateOutput().get(mapping.getKey()));
            }
        }
        return mapped.isEmpty() ? context : context.withMergedState(mapped);
    }
}
