package com.provisionlabs.agentsgraph;

import com.provisionlabs.agentsgraph.config.ConfigStore;
import com.provisionlabs.agentsgraph.config.GraphDefinition;
import com.provisionlabs.agentsgraph.config.InMemoryConfigStore;
import com.provisionlabs.agentsgraph.config.ProcessorDefinition;
import com.provisionlabs.agentsgraph.context.ExecutionContext;
import com.provisionlabs.agentsgraph.control.ControlPlane;
import com.provisionlabs.agentsgraph.control.DefaultControlPlane;
import com.provisionlabs.agentsgraph.control.GraphClassifier;
import com.provisionlabs.agentsgraph.control.TemplateGraphClassifier;
import com.provisionlabs.agentsgraph.engine.InMemoryOutputSink;
import com.provisionlabs.agentsgraph.engine.NoopOutputSink;
import com.provisionlabs.agentsgraph.engine.OutputSink;
import com.provisionlabs.agentsgraph.engine.Processor;
import com.provisionlabs.agentsgraph.engine.ProcessorHealthMonitor;
import com.provisionlabs.agentsgraph.engine.ProcessorLoader;
import com.provisionlabs.agentsgraph.engine.ProcessorRegistry;
import com.provisionlabs.agentsgraph.engine.RoutingDelegate;
import com.provisionlabs.agentsgraph.engine.RoutingDelegateRegistry;
import com.provisionlabs.agentsgraph.engine.RuntimeOrchestrator;
import com.provisionlabs.agentsgraph.trace.InMemoryTraceStore;
import com.provisionlabs.agentsgraph.trace.TraceStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

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
    private final OutputSink outputSink;
    private final RuntimeOrchestrator orchestrator;
    private final ControlPlane controlPlane;
    private volatile ProcessorHealthMonitor healthMonitor =
            new ProcessorHealthMonitor(ProcessorLoader.LoadResult.EMPTY);

    public AgentsGraphEngine(ConfigStore configStore, TraceStore traceStore,
                              ProcessorRegistry processorRegistry, RoutingDelegateRegistry delegateRegistry) {
        this(configStore, traceStore, processorRegistry, delegateRegistry,
                NoopOutputSink.INSTANCE, ForkJoinPool.commonPool());
    }

    public AgentsGraphEngine(ConfigStore configStore, TraceStore traceStore,
                              ProcessorRegistry processorRegistry, RoutingDelegateRegistry delegateRegistry,
                              OutputSink outputSink, Executor asyncExecutor) {
        this.configStore = configStore;
        this.traceStore = traceStore;
        this.processorRegistry = processorRegistry;
        this.delegateRegistry = delegateRegistry;
        this.outputSink = outputSink;
        this.orchestrator = new RuntimeOrchestrator(
                configStore, traceStore, processorRegistry, delegateRegistry, outputSink, asyncExecutor);
        this.controlPlane = new DefaultControlPlane(traceStore);
    }

    /** In-memory reference stack: handy for tests and getting started, not for production durability. */
    public static AgentsGraphEngine inMemory() {
        return new AgentsGraphEngine(
                new InMemoryConfigStore(), new InMemoryTraceStore(), new ProcessorRegistry(),
                new RoutingDelegateRegistry(), new InMemoryOutputSink(), ForkJoinPool.commonPool());
    }

    public void deployGraph(GraphDefinition graph) {
        configStore.putGraph(graph);
    }

    public void registerProcessor(String ref, Processor processor) {
        processorRegistry.register(ref, processor);
    }

    /**
     * Reflectively instantiates and registers every {@link ProcessorDefinition} (e.g. loaded from
     * a {@code ProcessorDefinitionStore}), and refreshes the engine's {@link ProcessorHealthMonitor}.
     */
    public ProcessorLoader.LoadResult loadProcessors(List<ProcessorDefinition> definitions) {
        ProcessorLoader.LoadResult result = new ProcessorLoader(processorRegistry).load(definitions);
        this.healthMonitor = new ProcessorHealthMonitor(result);
        return result;
    }

    public void registerRoutingDelegate(String ref, RoutingDelegate delegate) {
        delegateRegistry.register(ref, delegate);
    }

    public ExecutionContext execute(String graphId, ExecutionContext initialContext) {
        return orchestrator.run(graphId, initialContext);
    }

    /** Asynchronous counterpart of {@link #execute}. */
    public CompletableFuture<ExecutionContext> executeAsync(String graphId, ExecutionContext initialContext) {
        return orchestrator.runAsync(graphId, initialContext);
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

    public OutputSink getOutputSink() {
        return outputSink;
    }

    public ProcessorHealthMonitor getProcessorHealthMonitor() {
        return healthMonitor;
    }

    /**
     * Convenience factory for a {@link TemplateGraphClassifier} over this engine's own
     * {@link ConfigStore}, e.g. to pick a graph before calling {@link #execute}.
     */
    public GraphClassifier createTemplateGraphClassifier(String defaultGraphIdWithFile,
                                                           String defaultGraphIdWithoutFile) {
        return new TemplateGraphClassifier(configStore, defaultGraphIdWithFile, defaultGraphIdWithoutFile);
    }
}
