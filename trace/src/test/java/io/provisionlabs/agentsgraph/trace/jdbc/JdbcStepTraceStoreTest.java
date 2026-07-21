package io.provisionlabs.agentsgraph.trace.jdbc;

import io.provisionlabs.agentsgraph.trace.StepStatus;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcStepTraceStoreTest {

    private JdbcStepTraceStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:JdbcStepTraceStoreTest_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        store = new JdbcStepTraceStore(dataSource);
    }

    private static StepTraceRecord record(String flowId, long seq, long startedAt) {
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
    void roundTripsARecordWithAllFields() {
        StepTraceRecord original = record("f1", 0, 1000);
        original.setStatus(StepStatus.FAILED);
        original.setError("java.lang.IllegalStateException: boom\n\tat ...");
        original.setRestartable(false);
        store.append(original);

        StepTraceRecord loaded = store.find("f1", 0).orElseThrow();
        assertThat(loaded.getGraphId()).isEqualTo("g1");
        assertThat(loaded.getGraphVersion()).isEqualTo("v1");
        assertThat(loaded.getNodeId()).isEqualTo("n1");
        assertThat(loaded.getEdgeId()).isEqualTo("e1");
        assertThat(loaded.getStepId()).isEqualTo("s0");
        assertThat(loaded.getStepIndex()).isZero();
        assertThat(loaded.getProcessorRef()).isEqualTo("proc-0");
        assertThat(loaded.getInputContextJson()).isEqualTo("{\"input_data\":{}}");
        assertThat(loaded.getOutputJson()).isEqualTo("{\"out\":0}");
        assertThat(loaded.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(loaded.getError()).contains("boom");
        assertThat(loaded.isRestartable()).isFalse();
        assertThat(loaded.getStartedAtMillis()).isEqualTo(1000);
        assertThat(loaded.getDurationMs()).isEqualTo(5);
    }

    @Test
    void findByFlowReturnsRecordsInSeqOrder() {
        store.append(record("f2", 2, 3000));
        store.append(record("f2", 0, 1000));
        store.append(record("f2", 1, 2000));
        store.append(record("other", 0, 1000));

        assertThat(store.findByFlow("f2"))
                .extracting(StepTraceRecord::getSeq)
                .containsExactly(0L, 1L, 2L);
    }

    @Test
    void deleteOlderThanRemovesOnlyStaleRecords() {
        store.append(record("f3", 0, 1000));
        store.append(record("f3", 1, 5000));

        assertThat(store.deleteOlderThan(2000)).isEqualTo(1);
        assertThat(store.findByFlow("f3")).extracting(StepTraceRecord::getSeq).containsExactly(1L);
    }
}
