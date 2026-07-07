package com.provisionlabs.agentsgraph;

import com.provisionlabs.agentsgraph.config.ConfigStore;
import com.provisionlabs.agentsgraph.config.GraphDefinition;
import com.provisionlabs.agentsgraph.config.InMemoryConfigStore;
import com.provisionlabs.agentsgraph.context.ExecutionContext;
import com.provisionlabs.agentsgraph.control.ControlPlane;
import com.provisionlabs.agentsgraph.control.DefaultControlPlane;
import com.provisionlabs.agentsgraph.engine.Processor;
import com.provisionlabs.agentsgraph.engine.ProcessorRegistry;
import com.provisionlabs.agentsgraph.engine.RoutingDelegate;
import com.provisionlabs.agentsgraph.engine.RoutingDelegateRegistry;
import com.provisionlabs.agentsgraph.engine.RuntimeOrchestrator;
import com.provisionlabs.agentsgraph.trace.InMemoryTraceStore;
import com.provisionlabs.agentsgraph.trace.TraceStore;

/**
 * Facade tying the five declarative layers described in the AgentsGraph README together:
 * Config Store -&gt; Execution Context -&gt; Runtime Orchestrator -&gt; Trace Store -&gt; Control Plane.
 *
 * <pre>{@code
 * AgentsGraphEngine engine = AgentsGraphEngine.inMemory();
 * engine.registerProcessor("uppercase", (ctx, step) -> Map.of("out", ...));
 * engine.deployGraph(graph);
 * ExecutionContext result = engine.execute("support_flow", input, metadata);
 * }</pre>
 */
public final class AgentsGraphEngine {

    private final ConfigStore configStore;
    private final TraceStore traceStore;
    private final ProcessorRegistry processorRegistry;
    private final RoutingDelegateRegistry delegateRegistry;
    private final RuntimeOrchestrator orchestrator;
    private final ControlPlane controlPlane;

    public AgentsGraphEngine(ConfigStore configStore, TraceStore traceStore,
                              ProcessorRegistry processorRegistry, RoutingDelegateRegistry delegateRegistry) {
        this.configStore = configStore;
        this.traceStore = traceStore;
        this.processorRegistry = processorRegistry;
        this.delegateRegistry = delegateRegistry;
        this.orchestrator = new RuntimeOrchestrator(configStore, traceStore, processorRegistry, delegateRegistry);
        this.controlPlane = new DefaultControlPlane(traceStore);
    }

    public static AgentsGraphEngine inMemory() {
        return new AgentsGraphEngine(
                new InMemoryConfigStore(), new InMemoryTraceStore(), new ProcessorRegistry(), new RoutingDelegateRegistry());
    }

    public void deployGraph(GraphDefinition graph) {
        configStore.putGraph(graph);
    }

    public void registerProcessor(String ref, Processor processor) {
        processorRegistry.register(ref, processor);
    }

    public void registerRoutingDelegate(String ref, RoutingDelegate delegate) {
        delegateRegistry.register(ref, delegate);
    }

    public ExecutionContext execute(String graphId, ExecutionContext initialContext) {
        return orchestrator.run(graphId, initialContext);
    }

    public ConfigStore getConfigStore() {
        return configStore;
    }

    public TraceStore getTraceStore() {
        return traceStore;
    }

    public ControlPlane getControlPlane() {
        return controlPlane;
    }
}
