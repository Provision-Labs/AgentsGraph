package io.provisionlabs.agentsgraph.test;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.GraphConfigService;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcConfigStore;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;
import io.provisionlabs.agentsgraph.trace.TraceRecord;
import io.provisionlabs.agentsgraph.trace.jdbc.JdbcTraceStore;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test kit for running a real graph with selected processors replaced by mocks - the system's
 * routing/threading/fallback/tracing logic is exercised for real; the steps that would call an
 * LLM/OCR/classifier API are scripted {@link MockProcessor}s, so tests spend zero tokens and
 * need no network.
 *
 * <pre>{@code
 * // Graph + processor list come from the SAME SQL script production deploys with:
 * AgentsGraphTestHarness harness = AgentsGraphTestHarness.jdbc(h2DataSource);
 * harness.runSqlScript("/db/postgres/docscan_graph/graphs.sql");
 *
 * // Replace just the external-service steps with mocks:
 * MockProcessor ocr = harness.mockProcessor("docscan-ocr", Map.of("json", "{...}"));
 * MockProcessor llm = harness.mockProcessor("llm-completion", Map.of("llmContent", "ANSWER"));
 * harness.failProcessor("docscan-classify", "classifier down");   // or simulate an outage
 *
 * ExecutionContext result = harness.execute("ocr-accounting", Map.of("hasFile", true));
 * assertThat(result.getAccumulatedState()).containsEntry(...);
 * assertThat(llm.invocationCount()).isEqualTo(1);
 * assertThat(harness.trace(result).getTags()).contains("ocr_processed");
 * }</pre>
 *
 * <p>Mocks are registered as {@link GraphConfigService} programmatic processors, so they override
 * same-ref DB rows and survive {@link GraphConfigService#reload()} - exactly the seam a production
 * deployment uses for processors with live, injected dependencies. Register mocks <em>before</em>
 * the first {@link #execute} (the underlying service loads lazily); real (non-mock) processors
 * that need live dependencies can be supplied the same way via {@link #registerProcessor}.
 */
public final class AgentsGraphTestHarness {

    private final AgentsGraphEngine engine;
    private final DataSource dataSource;
    private final Map<String, Processor> programmaticProcessors = new LinkedHashMap<>();
    private GraphConfigService service;

    private AgentsGraphTestHarness(AgentsGraphEngine engine, DataSource dataSource) {
        this.engine = engine;
        this.dataSource = dataSource;
    }

    /** In-memory stores: deploy graphs programmatically via {@code getEngine().deployGraph(...)}. */
    public static AgentsGraphTestHarness inMemory() {
        return new AgentsGraphTestHarness(AgentsGraphEngine.inMemory(), null);
    }

    /**
     * JDBC-backed stores over {@code dataSource} (typically in-memory H2 standing in for the
     * production database), each ensuring its own schema - so a production SQL deployment script
     * can be applied verbatim via {@link #runSqlScript}.
     */
    public static AgentsGraphTestHarness jdbc(DataSource dataSource) {
        AgentsGraphEngine engine = new AgentsGraphEngine(new JdbcConfigStore(dataSource),
                new JdbcProcessorDefinitionStore(dataSource), new JdbcTraceStore(dataSource));
        return new AgentsGraphTestHarness(engine, dataSource);
    }

    /** Wrap an engine you built yourself (custom stores/sink/executor). */
    public static AgentsGraphTestHarness forEngine(AgentsGraphEngine engine) {
        return new AgentsGraphTestHarness(engine, null);
    }

    /** Runs a classpath SQL script (e.g. the production graph deployment script) - see {@link SqlScriptRunner}. */
    public AgentsGraphTestHarness runSqlScript(String classpathResource) {
        if (dataSource == null) {
            throw new IllegalStateException("runSqlScript needs a JDBC-backed harness - use AgentsGraphTestHarness.jdbc(dataSource)");
        }
        SqlScriptRunner.run(dataSource, classpathResource);
        return this;
    }

    /** Replaces the {@code ref} step with a mock that always returns {@code output}. */
    public MockProcessor mockProcessor(String ref, Map<String, Object> output) {
        return register(ref, MockProcessor.returning(output));
    }

    /** Replaces the {@code ref} step with a mock that always throws - for fallback-path tests. */
    public MockProcessor failProcessor(String ref, String message) {
        return register(ref, MockProcessor.failing(message));
    }

    /** Replaces the {@code ref} step with the given (mock or real) processor instance. */
    public <P extends Processor> P registerProcessor(String ref, P processor) {
        requireNotLoaded();
        programmaticProcessors.put(ref, processor);
        return processor;
    }

    /** Runs {@code graphId} with the given input data (loading graph/processors from the stores first). */
    public ExecutionContext execute(String graphId, Map<String, Object> inputData) {
        return getService().execute(graphId, ExecutionContext.newFlow(inputData, Map.of()));
    }

    /** The flow's {@link TraceRecord} (status, tags, telemetry) for asserting on the audit trail. */
    public TraceRecord trace(ExecutionContext result) {
        return engine.getTraceStore().find(result.getFlowId())
                .orElseThrow(() -> new IllegalStateException("No trace for flow " + result.getFlowId()));
    }

    /** The lazily-created {@link GraphConfigService} over the harness's engine and mocks. */
    public GraphConfigService getService() {
        if (service == null) {
            service = new GraphConfigService(engine, programmaticProcessors);
        }
        return service;
    }

    public AgentsGraphEngine getEngine() {
        return engine;
    }

    private MockProcessor register(String ref, MockProcessor mock) {
        return registerProcessor(ref, mock);
    }

    private void requireNotLoaded() {
        if (service != null) {
            throw new IllegalStateException(
                    "Register mocks before the first execute() - the GraphConfigService already snapshotted them");
        }
    }
}
