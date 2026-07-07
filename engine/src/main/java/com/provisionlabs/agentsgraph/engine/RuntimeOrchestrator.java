package com.provisionlabs.agentsgraph.engine;

import com.provisionlabs.agentsgraph.config.ConfigStore;
import com.provisionlabs.agentsgraph.config.EdgeDefinition;
import com.provisionlabs.agentsgraph.config.GraphDefinition;
import com.provisionlabs.agentsgraph.config.NodeDefinition;
import com.provisionlabs.agentsgraph.context.ExecutionContext;
import com.provisionlabs.agentsgraph.trace.ExecutionEvent;
import com.provisionlabs.agentsgraph.trace.ExecutionStatus;
import com.provisionlabs.agentsgraph.trace.RoutingOutcome;
import com.provisionlabs.agentsgraph.trace.TraceStore;

import java.time.Instant;
import java.util.Objects;

/**
 * Layer 3 - Runtime Orchestrator: the core execution loop mapping the conceptual Agent phases
 * (Observe -&gt; Plan -&gt; Act -&gt; Reflect) onto {@link Node} and {@link Edge} evaluation, walking a
 * {@link GraphDefinition} from its entry node until an {@link EdgeDefinition} with no
 * {@code nextNodeId} is reached, recording every hop into the {@link TraceStore}.
 */
public final class RuntimeOrchestrator {

    private final ConfigStore configStore;
    private final TraceStore traceStore;
    private final ProcessorRegistry processorRegistry;
    private final RoutingDelegateRegistry delegateRegistry;
    private final ConditionEngine conditionEngine = new ConditionEngine();

    public RuntimeOrchestrator(ConfigStore configStore, TraceStore traceStore,
                                ProcessorRegistry processorRegistry, RoutingDelegateRegistry delegateRegistry) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.traceStore = Objects.requireNonNull(traceStore, "traceStore");
        this.processorRegistry = Objects.requireNonNull(processorRegistry, "processorRegistry");
        this.delegateRegistry = Objects.requireNonNull(delegateRegistry, "delegateRegistry");
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

                EdgeDefinition edgeDefinition = graph.getEdge(decision.getNextEdgeId());
                Edge edge = new Edge(edgeDefinition, processorRegistry);

                // Act: run the selected edge's pipeline.
                EdgeResult edgeResult = edge.execute(context);
                context = edgeResult.getUpdatedContext();

                // Reflect: persist the routing decision, resulting snapshot and tags.
                RoutingOutcome outcome = new RoutingOutcome(
                        decision.getNextEdgeId(), decision.getConfidence(), decision.getSource().name());
                traceStore.appendEvent(initialContext.getFlowId(),
                        new ExecutionEvent(nodeDefinition.getId(), outcome, Instant.now(), context));
                traceStore.find(initialContext.getFlowId())
                        .ifPresent(record -> record.addTags(edgeResult.getTagsAdded()));

                currentNodeId = edgeDefinition.getNextNodeId();
            }
            traceStore.updateStatus(initialContext.getFlowId(), ExecutionStatus.COMPLETED);
            return context;
        } catch (RuntimeException e) {
            traceStore.updateStatus(initialContext.getFlowId(), ExecutionStatus.FAILED);
            throw e;
        }
    }
}
