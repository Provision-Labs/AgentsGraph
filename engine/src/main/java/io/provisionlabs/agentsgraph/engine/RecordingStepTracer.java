package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ContextJsonCodec;
import io.provisionlabs.agentsgraph.trace.StepStatus;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import io.provisionlabs.agentsgraph.trace.StepTraceStore;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug-mode {@link StepTracer}: turns every step execution into a {@link StepTraceRecord} - full
 * input-context snapshot, raw output, timing, and (on failure) the stack trace - appended to a
 * {@link StepTraceStore}. One instance per flow; {@code seq} is its monotonic step counter.
 */
public final class RecordingStepTracer implements StepTracer {

    private final StepTraceStore store;
    private final ContextJsonCodec codec;
    private final String flowId;
    private final String graphId;
    private final String graphVersion;
    private final AtomicLong seq = new AtomicLong();

    public RecordingStepTracer(StepTraceStore store, ContextJsonCodec codec,
                                String flowId, String graphId, String graphVersion) {
        this.store = store;
        this.codec = codec;
        this.flowId = flowId;
        this.graphId = graphId;
        this.graphVersion = graphVersion;
    }

    @Override
    public void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                ExecutionContext stepInput, Map<String, Object> rawOutput,
                                long startedAtMillis, long durationMs) {
        StepTraceRecord record = newRecord(nodeId, edge, step, stepIndex, stepInput, startedAtMillis, durationMs);
        ContextJsonCodec.Snapshot output = codec.snapshotMap(rawOutput);
        record.setOutputJson(output.getJson());
        record.setStatus(StepStatus.OK);
        store.append(record);
    }

    @Override
    public void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                             ExecutionContext stepInput, Throwable failure,
                             long startedAtMillis, long durationMs) {
        StepTraceRecord record = newRecord(nodeId, edge, step, stepIndex, stepInput, startedAtMillis, durationMs);
        record.setStatus(StepStatus.FAILED);
        StringWriter buffer = new StringWriter();
        failure.printStackTrace(new PrintWriter(buffer));
        record.setError(buffer.toString());
        store.append(record);
    }

    private StepTraceRecord newRecord(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                        ExecutionContext stepInput, long startedAtMillis, long durationMs) {
        StepTraceRecord record = new StepTraceRecord();
        record.setFlowId(flowId);
        record.setSeq(seq.getAndIncrement());
        record.setGraphId(graphId);
        record.setGraphVersion(graphVersion);
        record.setNodeId(nodeId);
        record.setEdgeId(edge.getId());
        record.setStepId(step.getId());
        record.setStepIndex(stepIndex);
        record.setProcessorRef(step.getProcessorRef());
        ContextJsonCodec.Snapshot input = codec.snapshotContext(stepInput);
        record.setInputContextJson(input.getJson());
        record.setRestartable(input.isRestartable());
        record.setStartedAtMillis(startedAtMillis);
        record.setDurationMs(durationMs);
        return record;
    }
}
