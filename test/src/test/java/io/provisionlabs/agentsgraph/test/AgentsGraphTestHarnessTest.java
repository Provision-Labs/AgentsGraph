package io.provisionlabs.agentsgraph.test;

import io.provisionlabs.agentsgraph.config.json.GraphJsonMapper;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentsGraphTestHarnessTest {

    private static JdbcDataSource freshH2() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:HarnessTest_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    private static AgentsGraphTestHarness jdbcHarness() {
        return AgentsGraphTestHarness.jdbc(freshH2())
                .runSqlScript("/sql/harness-test-graph.sql");
    }

    @Test
    void runsASqlDeployedGraphWithTheExternalStepMocked() {
        AgentsGraphTestHarness harness = jdbcHarness();
        MockProcessor ext = harness.mockProcessor("ext-service", Map.of("result", "canned"));
        harness.mockProcessor("fallback", Map.of("answer", "unused"));

        ExecutionContext result = harness.execute("harness-graph", Map.of("mode", "ext"));

        assertThat(result.getAccumulatedState()).containsEntry("result", "canned");
        assertThat(ext.invocationCount()).isEqualTo(1);
        assertThat(ext.lastInvocation().getInputData()).containsEntry("mode", "ext");
        assertThat(harness.trace(result).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(harness.trace(result).getTags()).contains("external_called");
    }

    @Test
    void failProcessorSimulatesAnOutageAndTheGraphFallsBack() {
        AgentsGraphTestHarness harness = jdbcHarness();
        harness.failProcessor("ext-service", "service down");
        harness.mockProcessor("fallback", Map.of("answer", "sorry"));

        ExecutionContext result = harness.execute("harness-graph", Map.of("mode", "ext"));

        assertThat(result.getAccumulatedState())
                .containsEntry("answer", "sorry")
                .containsKey("pipeline_error");
        // A fallback-handled failure finishes with ERROR (never COMPLETED), and the trace's
        // error field carries the failure's stack trace naming the failed edge/step.
        assertThat(harness.trace(result).getStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(harness.trace(result).getError()).contains("service down").contains("e_ext");
        assertThat(harness.trace(result).getTags()).contains("needs_review");
    }

    @Test
    void answeringMocksSeeTheIncomingContext() {
        AgentsGraphTestHarness harness = jdbcHarness();
        harness.registerProcessor("ext-service", MockProcessor.answering(
                (context, step) -> Map.of("result", "echo:" + context.getInputData().get("q"))));
        harness.mockProcessor("fallback", Map.of("answer", "unused"));

        ExecutionContext result = harness.execute("harness-graph", Map.of("mode", "ext", "q", "42"));

        assertThat(result.getAccumulatedState()).containsEntry("result", "echo:42");
    }

    @Test
    void inMemoryHarnessWorksWithProgrammaticallyDeployedGraphs() {
        AgentsGraphTestHarness harness = AgentsGraphTestHarness.inMemory();
        harness.getEngine().deployGraph(GraphJsonMapper.fromJson("{"
                + "\"id\": \"g\", \"version\": \"v1\", \"entry_node_id\": \"n0\","
                + "\"nodes\": [{\"id\": \"n0\", \"routing_strategy\": \"rules\", \"routing_table\": {\"default\": \"e0\"}}],"
                + "\"edges\": [{\"id\": \"e0\", \"steps\": [{\"id\": \"s0\", \"processor_id\": \"p\", \"output_to_save\": [\"out\"]}]}]"
                + "}"));
        harness.mockProcessor("p", Map.of("out", "ok"));

        ExecutionContext result = harness.execute("g", Map.of());

        assertThat(result.getAccumulatedState()).containsEntry("out", "ok");
    }

    @Test
    void mocksCanBeReRegisteredBetweenExecutions() {
        AgentsGraphTestHarness harness = jdbcHarness();
        harness.mockProcessor("ext-service", Map.of("result", "first"));
        harness.mockProcessor("fallback", Map.of("answer", "sorry"));

        ExecutionContext ok = harness.execute("harness-graph", Map.of("mode", "ext"));
        assertThat(ok.getAccumulatedState()).containsEntry("result", "first");

        // Same graph, same run of the test - now simulate the service going down.
        harness.failProcessor("ext-service", "went down");

        ExecutionContext fallback = harness.execute("harness-graph", Map.of("mode", "ext"));
        assertThat(fallback.getAccumulatedState()).containsEntry("answer", "sorry");
        assertThat(harness.trace(fallback).getTags()).contains("needs_review");
    }

    @Test
    void debugRunRecordsStepTracesAndResumesFromAFailedStep() {
        AgentsGraphTestHarness harness = jdbcHarness();
        harness.mockProcessor("ext-service", Map.of("result", "canned"));
        harness.mockProcessor("fallback", Map.of("answer", "unused"));

        ExecutionContext result = harness.executeDebug("harness-graph", Map.of("mode", "ext"));

        // Every step of the debug run is persisted (JDBC store) with its input and raw output.
        assertThat(harness.stepTraces(result)).isNotEmpty();
        assertThat(harness.stepTraces(result).get(0).getOutputJson()).contains("canned");

        // Resume the flow from its first step on the recorded data - with a DIFFERENT mock, to
        // prove the resumed run re-executes the step rather than replaying the old answer.
        MockProcessor secondTry = harness.mockProcessor("ext-service", Map.of("result", "resumed"));
        ExecutionContext resumed = harness.resumeFrom(result.getFlowId(), 0);

        assertThat(resumed.getAccumulatedState()).containsEntry("result", "resumed");
        assertThat(secondTry.invocationCount()).isEqualTo(1);
        assertThat(resumed.getMetadata()).containsEntry("parent_flow_id", result.getFlowId());
    }

    @Test
    void aDumpFromOneHarnessReplaysTheRecordedAnswersInAnother() {
        // "Production": a debug run records the external service's real answer.
        AgentsGraphTestHarness recording = jdbcHarness();
        recording.mockProcessor("ext-service", Map.of("result", "recorded-answer"));
        recording.mockProcessor("fallback", Map.of("answer", "unused"));
        ExecutionContext original = recording.executeDebug("harness-graph", Map.of("mode", "ext"));

        String dump = recording.stepTraceDump(original.getFlowId());

        // "Local": a fresh harness replays the dump - the recorded answers come back without the
        // original mocks (or, in real life, without the original external services).
        AgentsGraphTestHarness replaying = jdbcHarness();
        Map<String, MockProcessor> replayMocks = replaying.mocksFromDump(dump);
        replaying.mockProcessor("fallback", Map.of("answer", "unused"));

        ExecutionContext replayed = replaying.execute("harness-graph", Map.of("mode", "ext"));

        assertThat(replayed.getAccumulatedState()).containsEntry("result", "recorded-answer");
        assertThat(replayMocks).containsKey("ext-service");
        assertThat(replayMocks.get("ext-service").invocationCount()).isEqualTo(1);
    }

    @Test
    void mocksFromDumpCanBeLimitedToNamedRefs() {
        AgentsGraphTestHarness recording = jdbcHarness();
        recording.mockProcessor("ext-service", Map.of("result", "recorded-answer"));
        recording.mockProcessor("fallback", Map.of("answer", "unused"));
        ExecutionContext original = recording.executeDebug("harness-graph", Map.of("mode", "ext"));

        AgentsGraphTestHarness replaying = jdbcHarness();
        Map<String, MockProcessor> mocks = replaying.mocksFromDump(
                recording.stepTraceDump(original.getFlowId()), "some-other-ref");

        assertThat(mocks).isEmpty();
    }

    @Test
    void runSqlScriptRequiresAJdbcHarness() {
        assertThatThrownBy(() -> AgentsGraphTestHarness.inMemory().runSqlScript("/sql/harness-test-graph.sql"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc");
    }
}
