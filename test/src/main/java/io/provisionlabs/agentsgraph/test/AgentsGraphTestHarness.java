package io.provisionlabs.agentsgraph.test;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcConfigStore;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;
import io.provisionlabs.agentsgraph.trace.TraceRecord;
import io.provisionlabs.agentsgraph.trace.jdbc.JdbcTraceStore;

import javax.sql.DataSource;
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
 * <p>Mocks are registered as the engine's programmatic processors
 * ({@link AgentsGraphEngine#registerProcessor}), so they override same-ref DB rows and survive
 * {@link AgentsGraphEngine#reload()} - exactly the seam a production deployment uses for
 * processors with live, injected dependencies. Mocks can be (re-)registered at any time,
 * including between executions.
 */
public final class AgentsGraphTestHarness {

    private final AgentsGraphEngine engine;
    private final DataSource dataSource;

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
        return registerProcessor(ref, MockProcessor.returning(output));
    }

    /** Replaces the {@code ref} step with a mock that always throws - for fallback-path tests. */
    public MockProcessor failProcessor(String ref, String message) {
        return registerProcessor(ref, MockProcessor.failing(message));
    }

    /** Replaces the {@code ref} step with the given (mock or real) processor instance. */
    public <P extends Processor> P registerProcessor(String ref, P processor) {
        engine.registerProcessor(ref, processor);
        return processor;
    }

    /** Runs {@code graphId} with the given input data (the engine lazily loads graph/processors from its stores). */
    public ExecutionContext execute(String graphId, Map<String, Object> inputData) {
        return engine.execute(graphId, ExecutionContext.newFlow(inputData, Map.of()));
    }

    /** The flow's {@link TraceRecord} (status, tags, telemetry) for asserting on the audit trail. */
    public TraceRecord trace(ExecutionContext result) {
        return engine.getTraceStore().find(result.getFlowId())
                .orElseThrow(() -> new IllegalStateException("No trace for flow " + result.getFlowId()));
    }

    public AgentsGraphEngine getEngine() {
        return engine;
    }
}
