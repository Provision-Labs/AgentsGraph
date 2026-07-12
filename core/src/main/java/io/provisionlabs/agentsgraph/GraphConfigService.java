package io.provisionlabs.agentsgraph;

import io.provisionlabs.agentsgraph.config.ConfigStore;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.ProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.config.json.GraphJsonMapper;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;
import io.provisionlabs.agentsgraph.engine.ProcessorLoader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Universal, DB-driven loader/reloader for a deployed {@link AgentsGraphEngine} - the AgentsGraph
 * counterpart of a legacy hand-rolled {@code PipelineConfigService}: graph configs and processor
 * definitions live in the database ({@code agentsgraph_graph_config}/{@code agentsgraph_processor},
 * seeded by plain SQL insert scripts), and this service is the one place that materializes them
 * into a runnable engine - lazily on first use, and again on demand via {@link #reload()}.
 *
 * <p>What loading means here:
 * <ul>
 *   <li><b>Processors</b> are read from the engine's {@link ProcessorDefinitionStore} and
 *       reflectively instantiated/registered ({@link AgentsGraphEngine#loadProcessorsFromStore()}).
 *       {@link #reload()} re-reads the store, so changing a processor row's {@code params} (or
 *       adding a new row) takes effect without a redeploy.</li>
 *   <li><b>Programmatic processors</b> - the ones that need a live, injected dependency (an HTTP
 *       classifier client, a prompt-template lookup, ...) and therefore can't be instantiated
 *       reflectively from a DB row - are supplied to the constructor and (re-)registered after
 *       every load, <em>after</em> the store's definitions, so they always win over a same-ref DB
 *       row. Tests use the same seam to overlay stub-backed processors over the DB-seeded ones.</li>
 *   <li><b>Graphs</b> need no explicit reload: the orchestrator resolves the graph from the
 *       {@link ConfigStore} on every {@link #execute} call, so with a JDBC-backed store an
 *       {@code UPDATE agentsgraph_graph_config SET config = ...} is picked up by the very next
 *       execution.</li>
 * </ul>
 */
public final class GraphConfigService {

    private final AgentsGraphEngine engine;
    private final Map<String, Processor> programmaticProcessors;
    private final Object loadLock = new Object();
    private volatile boolean loaded;

    public GraphConfigService(AgentsGraphEngine engine) {
        this(engine, Collections.emptyMap());
    }

    public GraphConfigService(AgentsGraphEngine engine, Map<String, Processor> programmaticProcessors) {
        this.engine = engine;
        this.programmaticProcessors = programmaticProcessors == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(programmaticProcessors));
    }

    /**
     * Re-reads every {@code ProcessorDefinition} from the engine's store, reflectively
     * (re-)registers them, then re-applies the programmatic processors on top. Also marks the
     * service loaded, so an explicit startup call makes subsequent {@link #execute} calls skip the
     * lazy-load check.
     */
    public ProcessorLoader.LoadResult reload() {
        synchronized (loadLock) {
            ProcessorLoader.LoadResult result = engine.loadProcessorsFromStore();
            programmaticProcessors.forEach(engine::registerProcessor);
            loaded = true;
            return result;
        }
    }

    /** Runs the graph {@code graphId} (loading processors from the DB first, once). */
    public ExecutionContext execute(String graphId, ExecutionContext initialContext) {
        ensureLoaded();
        return engine.execute(graphId, initialContext);
    }

    /** Asynchronous counterpart of {@link #execute}. */
    public CompletableFuture<ExecutionContext> executeAsync(String graphId, ExecutionContext initialContext) {
        ensureLoaded();
        return engine.executeAsync(graphId, initialContext);
    }

    /** The latest revision of {@code graphId}, straight from the {@link ConfigStore}. */
    public GraphDefinition getGraph(String graphId) {
        ensureLoaded();
        return engine.getConfigStore().getGraph(graphId);
    }

    /** Every deployed graph, straight from the {@link ConfigStore}. */
    public List<GraphDefinition> getAllGraphs() {
        ensureLoaded();
        return engine.getConfigStore().findAll();
    }

    /** Serializes the currently-deployed revision of {@code graphId} back to graph JSON, for verification. */
    public String dumpGraphJson(String graphId) {
        return GraphJsonMapper.toJson(getGraph(graphId));
    }

    public AgentsGraphEngine getEngine() {
        return engine;
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
