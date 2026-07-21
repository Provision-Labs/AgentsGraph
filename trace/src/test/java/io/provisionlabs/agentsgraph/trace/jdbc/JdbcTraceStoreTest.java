package io.provisionlabs.agentsgraph.trace.jdbc;

import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ExecutionEvent;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import io.provisionlabs.agentsgraph.trace.RoutingOutcome;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import io.provisionlabs.agentsgraph.trace.TraceRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTraceStoreTest {

    private JdbcTraceStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:JdbcTraceStoreTest_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTraceStore.createSchema(dataSource);
        store = new JdbcTraceStore(dataSource);
    }

    @Test
    void tracksStatusTagsAndTelemetryAcrossAFlow() {
        store.startFlow("flow-1", "acme");

        ExecutionContext ctx = ExecutionContext.newFlow(Map.of(), Map.of());
        store.appendEvent("flow-1", new ExecutionEvent("node1", new RoutingOutcome("edge1", 1.0, "RULES"),
                Instant.now(), ctx));
        store.addTags("flow-1", List.of("billing", "vip"));
        store.updateStatus("flow-1", ExecutionStatus.COMPLETED);

        TraceRecord record = store.find("flow-1").orElseThrow();
        assertThat(record.getTenantId()).isEqualTo("acme");
        assertThat(record.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(record.getTags()).containsExactlyInAnyOrder("billing", "vip");
        assertThat(record.getTelemetry().getStepCount()).isEqualTo(1);
        // Audit log is served from the in-process cache within the same JdbcTraceStore instance.
        assertThat(record.getAuditLog()).hasSize(1);
        assertThat(record.getAuditLog().get(0).getNodeId()).isEqualTo("node1");
    }

    @Test
    void recordsAndRoundTripsTheFailureError() {
        store.startFlow("flow-err", "acme");

        assertThat(store.find("flow-err").orElseThrow().getError()).isNull();

        store.recordError("flow-err", "java.lang.IllegalStateException: OCR service unavailable\n\tat ...");
        store.updateStatus("flow-err", ExecutionStatus.ERROR);

        TraceRecord record = store.find("flow-err").orElseThrow();
        assertThat(record.getStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(record.getError()).contains("OCR service unavailable");
    }

    @Test
    void addingTagsIsCumulativeAcrossCalls() {
        store.startFlow("flow-2", "acme");

        store.addTags("flow-2", List.of("a"));
        store.addTags("flow-2", List.of("b"));

        assertThat(store.find("flow-2").orElseThrow().getTags()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void queryFiltersByStatusTenantAndTags() {
        store.startFlow("flow-a", "tenant-1");
        store.addTags("flow-a", List.of("vip"));
        store.updateStatus("flow-a", ExecutionStatus.COMPLETED);

        store.startFlow("flow-b", "tenant-2");
        store.updateStatus("flow-b", ExecutionStatus.FAILED);

        List<TraceRecord> completedForTenant1 = store.query(Set.of("vip"), ExecutionStatus.COMPLETED, "tenant-1");
        assertThat(completedForTenant1).extracting(TraceRecord::getFlowId).containsExactly("flow-a");

        List<TraceRecord> failed = store.query(null, ExecutionStatus.FAILED, null);
        assertThat(failed).extracting(TraceRecord::getFlowId).containsExactly("flow-b");
    }

    @Test
    void findReturnsEmptyForUnknownFlow() {
        assertThat(store.find("missing")).isEmpty();
    }

    private static StepTraceRecord stepRecord(String flowId, long seq, long startedAt) {
        StepTraceRecord record = new StepTraceRecord();
        record.setFlowId(flowId);
        record.setSeq(seq);
        record.setGraphId("g1");
        record.setGraphVersion("v1");
        record.setNodeId("n1");
        record.setEdgeId("e1");
        record.setStepId("s" + seq);
        record.setStepIndex((int) seq);
        record.setProcessorRef("proc-" + seq);
        record.setInputContextJson("{\"input_data\":{}}");
        record.setOutputJson("{\"out\":" + seq + "}");
        record.setStartedAtMillis(startedAt);
        record.setDurationMs(5);
        return record;
    }

    @Test
    void roundTripsAStepTraceRecordWithAllFields() {
        StepTraceRecord original = stepRecord("sf1", 0, 1000);
        original.setStatus(ExecutionStatus.FAILED);
        original.setError("java.lang.IllegalStateException: boom");
        original.setRestartable(false);
        store.appendStep(original);

        StepTraceRecord loaded = store.findStep("sf1", 0).orElseThrow();
        assertThat(loaded.getGraphId()).isEqualTo("g1");
        assertThat(loaded.getGraphVersion()).isEqualTo("v1");
        assertThat(loaded.getNodeId()).isEqualTo("n1");
        assertThat(loaded.getEdgeId()).isEqualTo("e1");
        assertThat(loaded.getStepId()).isEqualTo("s0");
        assertThat(loaded.getStepIndex()).isZero();
        assertThat(loaded.getProcessorRef()).isEqualTo("proc-0");
        assertThat(loaded.getInputContextJson()).isEqualTo("{\"input_data\":{}}");
        assertThat(loaded.getOutputJson()).isEqualTo("{\"out\":0}");
        assertThat(loaded.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(loaded.getError()).contains("boom");
        assertThat(loaded.isRestartable()).isFalse();
        assertThat(loaded.getStartedAtMillis()).isEqualTo(1000);
        assertThat(loaded.getDurationMs()).isEqualTo(5);
    }

    @Test
    void findStepsReturnsRecordsInSeqOrder() {
        store.appendStep(stepRecord("sf2", 2, 3000));
        store.appendStep(stepRecord("sf2", 0, 1000));
        store.appendStep(stepRecord("sf2", 1, 2000));
        store.appendStep(stepRecord("other", 0, 1000));

        assertThat(store.findSteps("sf2"))
                .extracting(StepTraceRecord::getSeq)
                .containsExactly(0L, 1L, 2L);
    }

    @Test
    void deleteStepsOlderThanRemovesOnlyStaleStepRecords() {
        store.appendStep(stepRecord("sf3", 0, 1000));
        store.appendStep(stepRecord("sf3", 1, 5000));

        assertThat(store.deleteStepsOlderThan(2000)).isEqualTo(1);
        assertThat(store.findSteps("sf3")).extracting(StepTraceRecord::getSeq).containsExactly(1L);
    }
}
