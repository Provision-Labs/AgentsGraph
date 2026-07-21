package io.provisionlabs.agentsgraph.test;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcConfigStore;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;
import io.provisionlabs.agentsgraph.trace.ContextJsonCodec;
import io.provisionlabs.agentsgraph.trace.StepStatus;
import io.provisionlabs.agentsgraph.trace.StepTraceJson;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import io.provisionlabs.agentsgraph.trace.TraceRecord;
import io.provisionlabs.agentsgraph.trace.jdbc.JdbcStepTraceStore;
import io.provisionlabs.agentsgraph.trace.jdbc.JdbcTraceStore;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                new JdbcProcessorDefinitionStore(dataSource), new JdbcTraceStore(dataSource),
                new JdbcStepTraceStore(dataSource));
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

    /**
     * Runs {@code graphId} in DEBUG mode: every step's input context and raw output land in the
     * step trace ({@link #stepTraces}), and the flow can be re-run from any step via
     * {@link #resumeFrom} - including a step that failed, after re-registering a fixed processor.
     */
    public ExecutionContext executeDebug(String graphId, Map<String, Object> inputData) {
        return engine.executeDebug(graphId, ExecutionContext.newFlow(inputData, Map.of()));
    }

    /** The step-level debug trace of a flow executed via {@link #executeDebug}, in execution order. */
    public List<StepTraceRecord> stepTraces(ExecutionContext result) {
        return stepTraces(result.getFlowId());
    }

    public List<StepTraceRecord> stepTraces(String flowId) {
        return engine.getStepTraces(flowId);
    }

    /** The flow's step trace as a self-contained JSON dump - see {@link #mocksFromDump}. */
    public String stepTraceDump(String flowId) {
        return getEngine().dumpStepTraces(flowId);
    }

    /**
     * Turns a step-trace dump (from {@code engine.dumpStepTraces} / {@link #stepTraceDump} of a
     * debug run - possibly against a PRODUCTION database) into replay mocks: for every processor
     * ref that succeeded in the dump, registers a {@link MockProcessor#returningSequence} that
     * answers with the recorded outputs in the recorded order. Re-executing the graph then
     * reproduces the original run's external-service answers with zero network - the recorded
     * failure, if any, is deliberately NOT replayed, so a test can assert what happens after the
     * fix while every upstream answer stays exactly as it was.
     *
     * <p>Pass {@code onlyRefs} to mock just the named processors (typically the external-service
     * steps) and leave the rest real.
     *
     * @return the registered mocks by processor ref, for invocation asserts
     */
    public Map<String, MockProcessor> mocksFromDump(String dumpJson, String... onlyRefs) {
        List<StepTraceRecord> records = StepTraceJson.fromJson(dumpJson);
        Set<String> filter = Set.of(onlyRefs);
        ContextJsonCodec codec = new ContextJsonCodec();

        Map<String, List<Map<String, Object>>> outputsByRef = new LinkedHashMap<>();
        for (StepTraceRecord record : records) {
            if (record.getStatus() != StepStatus.OK) {
                continue;
            }
            if (!filter.isEmpty() && !filter.contains(record.getProcessorRef())) {
                continue;
            }
            outputsByRef.computeIfAbsent(record.getProcessorRef(), ref -> new ArrayList<>())
                    .add(codec.readMap(record.getOutputJson()));
        }

        Map<String, MockProcessor> mocks = new LinkedHashMap<>();
        outputsByRef.forEach((ref, outputs) ->
                mocks.put(ref, registerProcessor(ref, MockProcessor.returningSequence(outputs))));
        return mocks;
    }

    /** Resumes a debug-traced flow from step {@code seq} on exactly the recorded input data. */
    public ExecutionContext resumeFrom(String flowId, long seq) {
        return engine.resumeFrom(flowId, seq);
    }

    /** Same, additionally merging {@code stateOverrides} into the restored accumulated state. */
    public ExecutionContext resumeFrom(String flowId, long seq, Map<String, Object> stateOverrides) {
        return engine.resumeFrom(flowId, seq, stateOverrides);
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
