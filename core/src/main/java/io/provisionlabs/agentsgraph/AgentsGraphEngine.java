package io.provisionlabs.agentsgraph;

import io.provisionlabs.agentsgraph.config.ConfigStore;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.InMemoryConfigStore;
import io.provisionlabs.agentsgraph.config.InMemoryProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import io.provisionlabs.agentsgraph.config.ProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.config.json.GraphJsonMapper;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.control.ControlPlane;
import io.provisionlabs.agentsgraph.control.DefaultControlPlane;
import io.provisionlabs.agentsgraph.control.GraphClassifier;
import io.provisionlabs.agentsgraph.control.TemplateGraphClassifier;
import io.provisionlabs.agentsgraph.engine.InMemoryOutputSink;
import io.provisionlabs.agentsgraph.engine.NoopOutputSink;
import io.provisionlabs.agentsgraph.engine.OutputSink;
import io.provisionlabs.agentsgraph.engine.Processor;
import io.provisionlabs.agentsgraph.engine.ProcessorHealthMonitor;
import io.provisionlabs.agentsgraph.engine.ProcessorLoader;
import io.provisionlabs.agentsgraph.engine.ProcessorRegistry;
import io.provisionlabs.agentsgraph.engine.RoutingDelegate;
import io.provisionlabs.agentsgraph.engine.RoutingDelegateRegistry;
import io.provisionlabs.agentsgraph.engine.RuntimeOrchestrator;
import io.provisionlabs.agentsgraph.trace.ContextJsonCodec;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import io.provisionlabs.agentsgraph.trace.InMemoryTraceStore;
import io.provisionlabs.agentsgraph.trace.StepTraceJson;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import io.provisionlabs.agentsgraph.trace.TraceRecord;
import io.provisionlabs.agentsgraph.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p><b>DB-driven loading &amp; reload.</b> Processor definitions are read from the engine's
 * {@link ProcessorDefinitionStore} - lazily on the first {@link #execute}, and again on demand via
 * {@link #reload()}, so changing a processor row's {@code params} (or adding a new row) takes
 * effect without a redeploy. Processors registered through {@link #registerProcessor} (the ones
 * needing live, injected dependencies that can't be instantiated reflectively from a DB row) are
 * pinned: they're re-applied after every load and always win over a same-ref DB row. Graphs need
 * no reload at all - the orchestrator resolves the graph from the {@link ConfigStore} on every
 * execution, so with a JDBC-backed store an {@code UPDATE agentsgraph_graph_config} is picked up
 * by the very next run.
 */
public final class AgentsGraphEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentsGraphEngine.class);

    private final ConfigStore configStore;
    private final ProcessorDefinitionStore processorDefinitionStore;
    private final TraceStore traceStore;
    private final ContextJsonCodec contextCodec = new ContextJsonCodec();
    private final ProcessorRegistry processorRegistry;
    private final RoutingDelegateRegistry delegateRegistry;
    private final OutputSink outputSink;
    private final RuntimeOrchestrator orchestrator;
    private final ControlPlane controlPlane;
    private final Map<String, Processor> programmaticProcessors = new ConcurrentHashMap<>();
    private final Object loadLock = new Object();
    private volatile boolean loaded;
    private volatile ProcessorHealthMonitor healthMonitor =
            new ProcessorHealthMonitor(ProcessorLoader.LoadResult.EMPTY);

    /**
     * Store-driven constructor - the recommended production shape: the engine never touches a
     * {@code DataSource} or any other storage detail itself; where configs/processors/traces live
     * is entirely the concern of the {@link ConfigStore}/{@link ProcessorDefinitionStore}/{@link
     * TraceStore} implementations passed in (in-memory, JSON-backed, JDBC-backed - each JDBC store
     * ensures its own schema on construction). Wire the three stores as beans and hand them to
     * this constructor.
     */
    public AgentsGraphEngine(ConfigStore configStore, ProcessorDefinitionStore processorDefinitionStore,
                              TraceStore traceStore) {
        this(configStore, processorDefinitionStore, traceStore, new ProcessorRegistry(),
                new RoutingDelegateRegistry(), NoopOutputSink.INSTANCE, ForkJoinPool.commonPool());
    }

    /**
     * Store-driven constructor additionally taking programmatic processors (see
     * {@link #registerProcessor}) - convenient for declarative wiring, e.g. a Spring
     * {@code <map>} of the processors that need live, injected dependencies.
     */
    public AgentsGraphEngine(ConfigStore configStore, ProcessorDefinitionStore processorDefinitionStore,
                              TraceStore traceStore, Map<String, Processor> programmaticProcessors) {
        this(configStore, processorDefinitionStore, traceStore);
        if (programmaticProcessors != null) {
            new LinkedHashMap<>(programmaticProcessors).forEach(this::registerProcessor);
        }
    }

    /**
     * Full constructor, also taking the registries, {@link OutputSink} and async {@link Executor}.
     * Prefer {@link #inMemory()} or the three-store constructor unless you need a custom
     * combination.
     */
    public AgentsGraphEngine(ConfigStore configStore, ProcessorDefinitionStore processorDefinitionStore,
                              TraceStore traceStore, ProcessorRegistry processorRegistry,
                              RoutingDelegateRegistry delegateRegistry, OutputSink outputSink,
                              Executor asyncExecutor) {
        this.configStore = configStore;
        this.processorDefinitionStore = processorDefinitionStore;
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
                new InMemoryConfigStore(), new InMemoryProcessorDefinitionStore(), new InMemoryTraceStore(),
                new ProcessorRegistry(), new RoutingDelegateRegistry(), new InMemoryOutputSink(),
                ForkJoinPool.commonPool());
    }

    public void deployGraph(GraphDefinition graph) {
        configStore.putGraph(graph);
    }

    /**
     * Registers a programmatic processor - one constructed by the application (typically because
     * it needs a live, injected dependency a DB row can't express). Programmatic processors are
     * pinned: {@link #reload()} re-applies them after loading the store's definitions, so they
     * always win over a same-ref DB row and survive reloads. Tests use the same seam to overlay
     * mock processors over SQL-seeded ones - at any time, including between executions.
     */
    public void registerProcessor(String ref, Processor processor) {
        programmaticProcessors.put(ref, processor);
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

    /**
     * Re-reads every {@link ProcessorDefinition} from the {@link ProcessorDefinitionStore},
     * reflectively (re-)registers them, then re-applies the pinned programmatic processors on
     * top. Called lazily by the first {@link #execute}; call explicitly after changing
     * {@code agentsgraph_processor} rows to pick the changes up without a redeploy.
     */
    public ProcessorLoader.LoadResult reload() {
        synchronized (loadLock) {
            ProcessorLoader.LoadResult result = loadProcessors(processorDefinitionStore.findAll());
            programmaticProcessors.forEach(processorRegistry::register);
            loaded = true;
            return result;
        }
    }

    /**
     * Seeds {@code definitions} into the {@link ProcessorDefinitionStore} the first time it's empty
     * (e.g. on first boot of a fresh JDBC schema), then loads them into the registry. A no-op seed if
     * the store already has entries — existing rows always win over the bundled defaults.
     */
    public ProcessorLoader.LoadResult seedAndLoadProcessors(List<ProcessorDefinition> defaultDefinitions) {
        if (defaultDefinitions != null && processorDefinitionStore.findAll().isEmpty()) {
            for (ProcessorDefinition definition : defaultDefinitions) {
                processorDefinitionStore.put(definition);
            }
        }
        return reload();
    }

    /**
     * Deploys {@code graph} into the {@link ConfigStore} only if no graph with the same id is present yet
     * (e.g. seeding a bundled graph on first boot of a fresh JDBC schema without overwriting an
     * already-customized one).
     */
    public void deployGraphIfAbsent(GraphDefinition graph) {
        if (configStore.findGraph(graph.getId()).isEmpty()) {
            configStore.putGraph(graph);
        }
    }

    public void registerRoutingDelegate(String ref, RoutingDelegate delegate) {
        delegateRegistry.register(ref, delegate);
    }

    /** Runs the graph {@code graphId} (loading processors from the store first, once, lazily). */
    public ExecutionContext execute(String graphId, ExecutionContext initialContext) {
        ensureLoaded();
        return orchestrator.run(graphId, initialContext);
    }

    /** Asynchronous counterpart of {@link #execute}. */
    public CompletableFuture<ExecutionContext> executeAsync(String graphId, ExecutionContext initialContext) {
        ensureLoaded();
        return orchestrator.runAsync(graphId, initialContext);
    }

    /**
     * Runs the graph with a live progress listener: {@code progressListener} receives
     * {@code stepStarted}/{@code stepSucceeded}/{@code stepFailed} for every step of this run
     * (works in normal AND debug mode; composed with the debug recorder). The hook behind
     * user-facing pipeline progress, e.g. a chatbot's status line. Listener exceptions never
     * break the flow.
     */
    public ExecutionContext execute(String graphId, ExecutionContext initialContext,
                                     io.provisionlabs.agentsgraph.engine.StepTracer progressListener) {
        ensureLoaded();
        return orchestrator.run(graphId, initialContext, progressListener);
    }

    /** Asynchronous counterpart of {@link #execute(String, ExecutionContext, io.provisionlabs.agentsgraph.engine.StepTracer)}. */
    public CompletableFuture<ExecutionContext> executeAsync(String graphId, ExecutionContext initialContext,
                                                              io.provisionlabs.agentsgraph.engine.StepTracer progressListener) {
        ensureLoaded();
        return orchestrator.runAsync(graphId, initialContext, progressListener);
    }

    /**
     * Runs the graph in DEBUG mode: every step's full input context and raw output are recorded
     * into the {@link TraceStore}'s step-level trace (see {@link #getStepTraces}), making the flow inspectable
     * after the fact and resumable from any recorded step via {@link #resumeFrom}. Equivalent to
     * {@link #execute} with {@code metadata[agentsgraph_debug]=true}.
     */
    public ExecutionContext executeDebug(String graphId, ExecutionContext initialContext) {
        ensureLoaded();
        return orchestrator.run(graphId,
                initialContext.withMergedMetadata(Map.of(RuntimeOrchestrator.DEBUG_METADATA_KEY, true)));
    }

    /** The recorded step-level debug trace of {@code flowId}, ordered by execution ({@code seq}). */
    public List<StepTraceRecord> getStepTraces(String flowId) {
        return traceStore.findSteps(flowId);
    }

    /**
     * The flow's step-level debug trace as a self-contained, pretty-printed JSON document -
     * attach it to a bug report or feed it to the {@code agentsgraph-test} harness
     * ({@code harness.mocksFromDump}) to replay the flow anywhere with the recorded
     * external-service answers.
     */
    public String dumpStepTraces(String flowId) {
        return StepTraceJson.toJson(traceStore.findSteps(flowId));
    }

    /**
     * Human-readable one-flow report: overall status/tags/error from the {@link TraceStore} plus
     * a step-by-step table from its step-level trace (when the flow ran in debug mode).
     * This is deliberately plain text - the intended "show me this flow" payload for whatever
     * ops surface the application already has (an admin endpoint, an actuator, a CLI).
     */
    public String describeFlow(String flowId) {
        StringBuilder report = new StringBuilder();
        TraceRecord trace = traceStore.find(flowId).orElse(null);
        if (trace == null) {
            report.append("Flow '").append(flowId).append("': no trace record found\n");
        } else {
            report.append("Flow '").append(flowId).append("': status ").append(trace.getStatus());
            if (!trace.getTags().isEmpty()) {
                report.append(", tags ").append(trace.getTags());
            }
            report.append('\n');
            if (trace.getError() != null && !trace.getError().isEmpty()) {
                report.append("error: ").append(firstLineOf(trace.getError())).append('\n');
            }
        }

        List<StepTraceRecord> steps = traceStore.findSteps(flowId);
        if (steps.isEmpty()) {
            report.append("(no step traces - the flow was not run in debug mode)\n");
            return report.toString();
        }
        report.append(String.format("%-5s %-18s %-22s %-14s %-22s %-7s %8s %-4s%n",
                "seq", "node", "edge", "step", "processor", "status", "ms", "restartable"));
        for (StepTraceRecord step : steps) {
            report.append(String.format("%-5d %-18s %-22s %-14s %-22s %-7s %8d %-4s%n",
                    step.getSeq(), step.getNodeId(), step.getEdgeId(), step.getStepId(),
                    step.getProcessorRef(), step.getStatus(), step.getDurationMs(),
                    step.isRestartable() ? "yes" : "NO"));
            if (step.getStatus() == ExecutionStatus.FAILED && step.getError() != null) {
                report.append("      error: ").append(firstLineOf(step.getError())).append('\n');
            }
        }
        return report.toString();
    }

    private static String firstLineOf(String text) {
        int newline = text.indexOf('\n');
        return (newline < 0 ? text : text.substring(0, newline)).trim();
    }

    /** Resumes {@code flowId} from step {@code seq} on exactly the data that step originally saw. */
    public ExecutionContext resumeFrom(String flowId, long seq) {
        return resumeFrom(flowId, seq, Map.of());
    }

    /**
     * Resumes a debug-traced flow from a recorded step: restores the context from the step's
     * recorded input snapshot, applies {@code stateOverrides} on top of {@code accumulated_state}
     * (e.g. a corrected value when debugging bad data), and re-executes the graph from exactly
     * that step - earlier steps don't run again. The resumed run is a NEW flow (its metadata
     * carries {@code parent_flow_id}/{@code resumed_from_seq} for lineage) and runs in debug mode
     * itself, so it can be resumed again.
     *
     * <p>Everything from the resume point on executes live (delegates/LLMs may answer
     * differently than in the original run); replaying recorded answers is what the
     * {@code agentsgraph-test} mock harness is for.
     */
    public ExecutionContext resumeFrom(String flowId, long seq, Map<String, Object> stateOverrides) {
        StepTraceRecord record = traceStore.findStep(flowId, seq)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No step trace for flow '" + flowId + "' seq " + seq
                                + " - was the flow executed in debug mode (executeDebug)?"));
        if (!record.isRestartable()) {
            throw new IllegalStateException("Step trace flow '" + flowId + "' seq " + seq
                    + " is not restartable: its input snapshot dropped or truncated a value"
                    + " (see ContextJsonCodec markers in the snapshot)");
        }
        GraphDefinition graph = configStore.getGraph(record.getGraphId());
        if (!Objects.equals(graph.getVersion(), record.getGraphVersion())) {
            log.warn("Resuming flow '{}' seq {} recorded against graph '{}' version '{}', but the deployed"
                            + " version is now '{}' - the resumed run executes against the CURRENT graph",
                    flowId, seq, record.getGraphId(), record.getGraphVersion(), graph.getVersion());
        }

        ContextJsonCodec.DecodedContext decoded = contextCodec.readContext(record.getInputContextJson());
        Map<String, Object> metadata = new LinkedHashMap<>(decoded.getMetadata());
        metadata.put("parent_flow_id", flowId);
        metadata.put("resumed_from_seq", seq);
        metadata.put(RuntimeOrchestrator.DEBUG_METADATA_KEY, true);

        ExecutionContext context = ExecutionContext.newFlow(decoded.getInputData(), metadata)
                .withMergedState(decoded.getAccumulatedState());
        if (stateOverrides != null && !stateOverrides.isEmpty()) {
            context = context.withMergedState(stateOverrides);
        }

        ensureLoaded();
        return orchestrator.resume(record.getGraphId(), context,
                record.getNodeId(), record.getEdgeId(), record.getStepIndex());
    }

    /** Serializes the currently-deployed revision of {@code graphId} back to graph JSON, for verification. */
    public String dumpGraphJson(String graphId) {
        return GraphJsonMapper.toJson(configStore.getGraph(graphId));
    }

    public ConfigStore getConfigStore() {
        return configStore;
    }

    public ProcessorDefinitionStore getProcessorDefinitionStore() {
        return processorDefinitionStore;
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

    private void ensureLoaded() {
        if (!loaded) {
            synchronized (loadLock) {
                if (!loaded) {
                    reload();
                }
            }
        }
    }
}
