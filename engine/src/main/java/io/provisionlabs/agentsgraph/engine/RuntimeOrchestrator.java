package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.ConfigStore;
import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ContextJsonCodec;
import io.provisionlabs.agentsgraph.trace.ExecutionEvent;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import io.provisionlabs.agentsgraph.trace.RoutingOutcome;
import io.provisionlabs.agentsgraph.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
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
 *
 * <p><b>Failure observability.</b> Every failure is reported three ways at once: logged through
 * SLF4J (so it lands in the application's console/file logs with the full stack trace, naming the
 * graph, flow, node and failed edge/step), persisted into the {@link TraceStore} record's
 * {@code error} field (full stack trace), and reflected in the flow's final status -
 * {@link ExecutionStatus#ERROR} when a {@code fallback_edge_id} handled the failure (the flow
 * finished, but did NOT complete its intended path - never {@code COMPLETED}), or
 * {@link ExecutionStatus#FAILED} when the failure aborted the flow and propagated to the caller.
 */
public final class RuntimeOrchestrator {

    /**
     * Metadata key switching a flow into debug mode ({@code true}/"true"): every step's input
     * context and raw output are recorded into the {@link TraceStore}'s step-level trace,
     * enabling post-mortem inspection and {@link #resume}.
     */
    public static final String DEBUG_METADATA_KEY = "agentsgraph_debug";

    private static final Logger log = LoggerFactory.getLogger(RuntimeOrchestrator.class);

    private final ConfigStore configStore;
    private final TraceStore traceStore;
    private final ProcessorRegistry processorRegistry;
    private final RoutingDelegateRegistry delegateRegistry;
    private final OutputSink outputSink;
    private final Executor asyncExecutor;
    private final ConditionEngine conditionEngine = new ConditionEngine();
    private final ContextJsonCodec contextCodec = new ContextJsonCodec();

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
        return runFlow(graphId, initialContext, null);
    }

    /**
     * Resumes a flow mid-edge: starts at step {@code stepIndex} of {@code edgeId} (under
     * {@code nodeId}, whose {@code fallback_edge_id} still applies) instead of routing from the
     * graph's entry node, then continues normally. The caller is responsible for restoring
     * {@code initialContext} from the recorded input snapshot of exactly that step - see
     * {@code AgentsGraphEngine.resumeFrom}.
     */
    public ExecutionContext resume(String graphId, ExecutionContext initialContext,
                                     String nodeId, String edgeId, int stepIndex) {
        return runFlow(graphId, initialContext, new ResumePoint(nodeId, edgeId, stepIndex));
    }

    /** Where a resumed flow re-enters the graph: a specific step of a specific edge. */
    private static final class ResumePoint {
        final String nodeId;
        final String edgeId;
        final int stepIndex;

        ResumePoint(String nodeId, String edgeId, int stepIndex) {
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
            this.edgeId = Objects.requireNonNull(edgeId, "edgeId");
            this.stepIndex = stepIndex;
        }
    }

    private ExecutionContext runFlow(String graphId, ExecutionContext initialContext, ResumePoint resume) {
        GraphDefinition graph = configStore.getGraph(graphId);
        String tenantId = String.valueOf(initialContext.getMetadata().get("tenant_id"));
        traceStore.startFlow(initialContext.getFlowId(), tenantId);
        StepTracer tracer = stepTracerFor(graph, initialContext);

        ExecutionContext context = initialContext;
        String currentNodeId = resume != null ? resume.nodeId : graph.getEntryNodeId();
        ResumePoint pendingResume = resume;
        boolean fellBack = false;

        try {
            while (currentNodeId != null) {
                NodeDefinition nodeDefinition = graph.getNode(currentNodeId);

                RoutingDecision decision;
                int startStepIndex = 0;
                if (pendingResume != null) {
                    // Resumed flow: skip routing on the first hop - re-enter the recorded edge at
                    // the recorded step, on the restored context.
                    decision = new RoutingDecision(pendingResume.edgeId, 1.0, RoutingSource.RESUME);
                    startStepIndex = pendingResume.stepIndex;
                    pendingResume = null;
                } else {
                    // Plan: evaluate the node's routing table/delegate against the current context.
                    Node node = new Node(nodeDefinition, conditionEngine, delegateRegistry);
                    decision = node.route(context);
                    context = applyOutputMapping(context, nodeDefinition, decision);
                }

                // Routing itself falling back (no rule matched / the delegate threw or returned
                // nothing) is a failure too: the flow abandons its intended path just like an
                // edge-step failure, so it gets the same observability - SLF4J log with the
                // preserved cause, error persisted into the trace, and a final status of ERROR.
                if (decision.getSource() == RoutingSource.FALLBACK) {
                    String reason = decision.getFailureReason() == null
                            ? "routing fell back" : decision.getFailureReason();
                    if (decision.getFailure() != null) {
                        log.error("Flow '{}' (graph '{}'): node '{}' routing fell back to edge '{}' - {}",
                                initialContext.getFlowId(), graphId, nodeDefinition.getId(),
                                decision.getNextEdgeId(), reason, decision.getFailure());
                        traceStore.recordError(initialContext.getFlowId(),
                                "Node '" + nodeDefinition.getId() + "' routing fell back to edge '"
                                        + decision.getNextEdgeId() + "': " + reason
                                        + System.lineSeparator() + stackTraceOf(decision.getFailure()));
                    } else {
                        log.error("Flow '{}' (graph '{}'): node '{}' routing fell back to edge '{}' - {}",
                                initialContext.getFlowId(), graphId, nodeDefinition.getId(),
                                decision.getNextEdgeId(), reason);
                        traceStore.recordError(initialContext.getFlowId(),
                                "Node '" + nodeDefinition.getId() + "' routing fell back to edge '"
                                        + decision.getNextEdgeId() + "': " + reason);
                    }
                    fellBack = true;
                    context = context.withMergedState(Map.of("pipeline_error", reason));
                }

                EdgeDefinition edgeDefinition = graph.getEdge(decision.getNextEdgeId());
                Edge edge = new Edge(edgeDefinition, processorRegistry);

                // Act: run the selected edge's pipeline, falling back to fallback_edge_id (if set)
                // rather than aborting the flow when the edge's own step pipeline throws.
                EdgeResult edgeResult;
                try {
                    edgeResult = edge.executeFrom(startStepIndex, context, nodeDefinition.getId(), tracer);
                } catch (RuntimeException stepFailure) {
                    String fallbackEdgeId = nodeDefinition.getFallbackEdgeId();
                    if (fallbackEdgeId == null || fallbackEdgeId.equals(edgeDefinition.getId())) {
                        throw stepFailure;
                    }
                    log.error("Flow '{}' (graph '{}'): edge '{}' failed after node '{}' - running fallback edge '{}'",
                            initialContext.getFlowId(), graphId, edgeDefinition.getId(),
                            nodeDefinition.getId(), fallbackEdgeId, stepFailure);
                    traceStore.recordError(initialContext.getFlowId(), stackTraceOf(stepFailure));
                    fellBack = true;
                    edgeDefinition = graph.getEdge(fallbackEdgeId);
                    edge = new Edge(edgeDefinition, processorRegistry);
                    context = context.withMergedState(Map.of("pipeline_error", String.valueOf(stepFailure.getMessage())));
                    decision = new RoutingDecision(fallbackEdgeId, 0.0, RoutingSource.FALLBACK);
                    edgeResult = edge.execute(context, nodeDefinition.getId(), tracer);
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
            // A flow that only finished thanks to a fallback edge did NOT complete its intended
            // path - report ERROR (with the failure recorded above), never COMPLETED.
            traceStore.updateStatus(initialContext.getFlowId(),
                    fellBack ? ExecutionStatus.ERROR : ExecutionStatus.COMPLETED);
            return context;
        } catch (RuntimeException e) {
            log.error("Flow '{}' (graph '{}') failed at node '{}': {}",
                    initialContext.getFlowId(), graphId, currentNodeId, e.getMessage(), e);
            traceStore.recordError(initialContext.getFlowId(), stackTraceOf(e));
            traceStore.updateStatus(initialContext.getFlowId(), ExecutionStatus.FAILED);
            throw e;
        }
    }

    /**
     * A {@link RecordingStepTracer} (writing into the {@link TraceStore}'s step-level trace)
     * when the context's metadata carries {@link #DEBUG_METADATA_KEY}; {@link StepTracer#NOOP}
     * otherwise - the normal path never pays for step tracing.
     */
    private StepTracer stepTracerFor(GraphDefinition graph, ExecutionContext initialContext) {
        if (!isDebug(initialContext)) {
            return StepTracer.NOOP;
        }
        return new RecordingStepTracer(traceStore, contextCodec,
                initialContext.getFlowId(), graph.getId(), graph.getVersion());
    }

    private static boolean isDebug(ExecutionContext context) {
        Object flag = context.getMetadata().get(DEBUG_METADATA_KEY);
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    /** Full stack trace of {@code failure} as text, for the trace record's {@code error} field. */
    private static String stackTraceOf(Throwable failure) {
        StringWriter buffer = new StringWriter();
        failure.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
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
