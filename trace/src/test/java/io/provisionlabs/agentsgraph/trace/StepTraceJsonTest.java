package io.provisionlabs.agentsgraph.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepTraceJsonTest {

    private static StepTraceRecord record(long seq, StepStatus status) {
        StepTraceRecord record = new StepTraceRecord();
        record.setFlowId("f1");
        record.setSeq(seq);
        record.setGraphId("g1");
        record.setGraphVersion("v1");
        record.setNodeId("n1");
        record.setEdgeId("e1");
        record.setStepId("s" + seq);
        record.setStepIndex((int) seq);
        record.setProcessorRef("proc-" + seq);
        record.setInputContextJson("{\"input_data\":{\"text\":\"hello\"}}");
        record.setOutputJson(status == StepStatus.OK ? "{\"out\":" + seq + "}" : null);
        record.setStatus(status);
        record.setError(status == StepStatus.FAILED ? "java.lang.IllegalStateException: boom" : null);
        record.setRestartable(seq != 1);
        record.setStartedAtMillis(1000 + seq);
        record.setDurationMs(7);
        return record;
    }

    @Test
    void dumpRoundTripsAllFieldsInOrder() {
        List<StepTraceRecord> original = List.of(record(0, StepStatus.OK), record(1, StepStatus.FAILED));

        String json = StepTraceJson.toJson(original);
        List<StepTraceRecord> parsed = StepTraceJson.fromJson(json);

        assertThat(parsed).hasSize(2);
        StepTraceRecord ok = parsed.get(0);
        assertThat(ok.getFlowId()).isEqualTo("f1");
        assertThat(ok.getSeq()).isZero();
        assertThat(ok.getGraphVersion()).isEqualTo("v1");
        assertThat(ok.getProcessorRef()).isEqualTo("proc-0");
        assertThat(ok.getInputContextJson()).contains("hello");
        assertThat(ok.getOutputJson()).isEqualTo("{\"out\":0}");
        assertThat(ok.getStatus()).isEqualTo(StepStatus.OK);
        assertThat(ok.isRestartable()).isTrue();
        assertThat(ok.getStartedAtMillis()).isEqualTo(1000);
        assertThat(ok.getDurationMs()).isEqualTo(7);

        StepTraceRecord failed = parsed.get(1);
        assertThat(failed.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(failed.getError()).contains("boom");
        assertThat(failed.getOutputJson()).isNull();
        assertThat(failed.isRestartable()).isFalse();
    }
}
