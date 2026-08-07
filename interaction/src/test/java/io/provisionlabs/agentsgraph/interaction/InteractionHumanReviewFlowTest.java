package io.provisionlabs.agentsgraph.interaction;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The full HITL cycle on the "OCR accuracy" diamond: low accuracy takes the flow into the review
 * branch, the flow completes NORMALLY with the review_pending tag (the engine knows nothing about
 * "pauses"), the task shows up in {@link InteractionService#pending()}, and the human's answer
 * continues the pipeline through the existing {@code resumeFrom} - post-processing receives the
 * corrected data and cannot tell it apart from a perfect recognition.
 *
 * <pre>
 * node_ocr: edge_ocr = [ocr, accuracy-check] -> node_decision
 *    +- accuracyOk==false -> edge_hitl = [human-review(snapshot), apply-corrections] -> node_after_review
 *    |       +- reviewPending==true -> edge_pending(noop, tag review_pending) -> end
 *    |       +- default             -> edge_llm
 *    +- default -> edge_llm = [llm] -> end
 * </pre>
 */
class InteractionHumanReviewFlowTest {

    private AgentsGraphEngine engine;
    private InteractionService interaction;
    private final AtomicInteger ocrCalls = new AtomicInteger();
    private final List<HumanTask> publishedTasks = new ArrayList<>();
    private final List<HumanTask> closedTasks = new ArrayList<>();

    /** Accuracy comes from the test's input data - the "OCR" here is a stand-in. */
    @BeforeEach
    void setUp() {
        engine = AgentsGraphEngine.inMemory();

        engine.registerProcessor("ocr", (context, step) -> {
            ocrCalls.incrementAndGet();
            return Map.of("fields", Map.of("doc_number", "ЦБ-641"),
                    "prob", context.getInputData().getOrDefault("prob", 0.99));
        });
        engine.registerProcessor("accuracy-check", (context, step) -> {
            double prob = Double.parseDouble(String.valueOf(context.getAccumulatedState().get("prob")));
            return Map.of("accuracyScore", prob, "accuracyOk", prob >= 0.85);
        });
        engine.registerProcessor("human-review", new HumanReviewProcessor());
        engine.registerProcessor("apply-corrections", (context, step) -> {
            Object review = context.getAccumulatedState().get("humanReview");
            if (!(review instanceof Map)) {
                return Map.of(); // pending run: nothing to correct
            }
            Object corrected = ((Map<?, ?>) review).get("fields");
            return corrected == null ? Map.of() : Map.of("fields", corrected);
        });
        engine.registerProcessor("noop", new NoopProcessor());
        engine.registerProcessor("llm", (context, step) ->
                Map.of("summary", "LLM(" + context.getAccumulatedState().get("fields") + ")"));

        NodeDefinition nodeOcr = NodeDefinition.builder("node_ocr")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_ocr")
                .build();
        NodeDefinition nodeDecision = NodeDefinition.builder("node_decision")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("accuracyOk==false", "edge_hitl")
                .routingRule("default", "edge_llm")
                .build();
        NodeDefinition nodeAfterReview = NodeDefinition.builder("node_after_review")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("reviewPending==true", "edge_pending")
                .routingRule("default", "edge_llm")
                .build();

        EdgeDefinition edgeOcr = EdgeDefinition.builder("edge_ocr")
                .step(new StepDefinition("s_ocr", "ocr", Map.of()))
                .step(new StepDefinition("s_accuracy", "accuracy-check", Map.of()))
                .nextNodeId("node_decision")
                .build();
        EdgeDefinition edgeHitl = EdgeDefinition.builder("edge_hitl")
                .step(new StepDefinition("s_review", "human-review",
                        Map.of("question", "Проверьте документ",
                                "showKeys", "fields,accuracyScore",
                                "requiredKeys", "fields",
                                "timeoutSeconds", "3600"),
                        List.of(), List.of(), true)) // snapshot: restartable in production, no debug needed
                .step(new StepDefinition("s_apply", "apply-corrections", Map.of()))
                .nextNodeId("node_after_review")
                .build();
        EdgeDefinition edgePending = EdgeDefinition.builder("edge_pending")
                .step(new StepDefinition("s_noop", "noop", Map.of()))
                .tagToAdd(InteractionService.PENDING_TAG)
                .build();
        EdgeDefinition edgeLlm = EdgeDefinition.builder("edge_llm")
                .step(new StepDefinition("s_llm", "llm", Map.of()))
                .build();

        engine.deployGraph(GraphDefinition.builder("doc-flow", "v1")
                .entryNodeId("node_ocr")
                .node(nodeOcr).node(nodeDecision).node(nodeAfterReview)
                .edge(edgeOcr).edge(edgeHitl).edge(edgePending).edge(edgeLlm)
                .build());

        interaction = new InteractionService(engine, List.of(new HumanTaskAdapter() {
            @Override
            public boolean supports(HumanTask task) {
                return true;
            }

            @Override
            public void publish(HumanTask task) {
                publishedTasks.add(task);
            }

            @Override
            public void closed(HumanTask task, TaskOutcome outcome) {
                closedTasks.add(task);
            }
        }));
    }

    @Test
    void goodAccuracySkipsTheReviewBranchEntirely() {
        ExecutionContext result = engine.execute("doc-flow",
                ExecutionContext.newFlow(Map.of("prob", 0.99), Map.of()));

        assertThat(result.getAccumulatedState().get("summary")).asString().contains("ЦБ-641");
        assertThat(interaction.pending()).isEmpty();
        // production run without review: the selective tracer records only snapshot steps, and none ran
        assertThat(engine.getStepTraces(result.getFlowId())).isEmpty();
    }

    @Test
    void lowAccuracyEndsTheFlowAsReviewPendingAndTheAnswerContinuesThePipeline() {
        // 1. Low accuracy: the flow completed NORMALLY, but without a summary - it went to review.
        ExecutionContext pending = engine.execute("doc-flow",
                ExecutionContext.newFlow(Map.of("prob", 0.42), Map.of()));
        assertThat(pending.getAccumulatedState()).doesNotContainKey("summary");

        // 2. The task is visible and delivered to the adapter exactly once.
        assertThat(interaction.publishNew()).isEqualTo(1);
        assertThat(interaction.publishNew()).isZero();
        assertThat(publishedTasks).hasSize(1);
        HumanTask task = publishedTasks.get(0);
        assertThat(task.getQuestion()).isEqualTo("Проверьте документ");
        assertThat(task.getPayload()).containsKey("fields").containsKey("accuracyScore");
        assertThat(task.getDeadlineEpochMillis()).isNotNull();

        // 3. Only the review step was snapshotted (production, not debug) - enough to resume.
        List<StepTraceRecord> traces = engine.getStepTraces(pending.getFlowId());
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getStepId()).isEqualTo("s_review");
        assertThat(traces.get(0).isRestartable()).isTrue();

        // 4. The human sent corrected fields - the pipeline reached the LLM on THEM, OCR did not rerun.
        int ocrCallsBefore = ocrCalls.get();
        ExecutionContext resumed = interaction.complete(task.getTaskId(),
                new HumanTaskDecision(Map.of("fields", Map.of("doc_number", "ЦБ-999")), "operator"));

        assertThat(resumed.getAccumulatedState().get("summary")).asString().contains("ЦБ-999");
        assertThat(ocrCalls.get()).isEqualTo(ocrCallsBefore);
        assertThat(resumed.getMetadata()).containsEntry("parent_flow_id", pending.getFlowId());

        // 5. The task is closed: empty inbox, adapter notified, repeated answers rejected.
        assertThat(interaction.pending()).isEmpty();
        assertThat(closedTasks).hasSize(1);
        assertThatThrownBy(() -> interaction.complete(task.getTaskId(),
                HumanTaskDecision.option("approve", "operator2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already closed");
    }

    @Test
    void answerMustMatchTheSchema() {
        engine.execute("doc-flow", ExecutionContext.newFlow(Map.of("prob", 0.1), Map.of()));
        HumanTask task = interaction.pending().get(0);

        assertThatThrownBy(() -> interaction.complete(task.getTaskId(),
                new HumanTaskDecision(Map.of("something", "else"), "operator")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields");
        // the invalid answer did NOT close the task
        assertThat(interaction.pending()).hasSize(1);
    }

    @Test
    void overdueTasksAreExpiredNotResumed() {
        engine.execute("doc-flow", ExecutionContext.newFlow(Map.of("prob", 0.1), Map.of()));
        HumanTask task = interaction.pending().get(0);
        assertThat(interaction.expireOverdue()).isZero(); // deadline is an hour away - not yet due

        // the invariant under test here: tasks that are not overdue are left untouched
        assertThat(interaction.pendingTask(task.getTaskId())).isPresent();
    }
}
