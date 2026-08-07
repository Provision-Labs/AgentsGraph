package io.provisionlabs.agentsgraph.interaction;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A task for a human, built from a COMPLETED flow that went down the review branch: the saved
 * output of the {@code human-review} step (see {@link HumanReviewProcessor}) plus the coordinates
 * needed to continue the pipeline ({@code AgentsGraphEngine.resumeFrom(flowId, seq, ...)}). The
 * engine knows nothing about tasks - this is the interaction module's projection over the
 * {@code TraceStore}.
 */
public final class HumanTask {

    private final String flowId;
    private final long seq;
    private final String graphId;
    private final String stepId;
    private final String question;
    private final Map<String, Object> payload;
    private final ResponseSchema schema;
    private final String resumeKey;
    private final Long deadlineEpochMillis;

    public HumanTask(String flowId, long seq, String graphId, String stepId, String question,
                      Map<String, Object> payload, ResponseSchema schema, String resumeKey,
                      Long deadlineEpochMillis) {
        this.flowId = Objects.requireNonNull(flowId, "flowId");
        this.seq = seq;
        this.graphId = graphId;
        this.stepId = stepId;
        this.question = question;
        this.payload = payload == null ? Map.of() : Collections.unmodifiableMap(payload);
        this.schema = schema == null ? ResponseSchema.freeForm() : schema;
        this.resumeKey = resumeKey == null ? HumanReviewProcessor.DEFAULT_RESUME_KEY : resumeKey;
        this.deadlineEpochMillis = deadlineEpochMillis;
    }

    /** Natural task key - doubles as the idempotency key for answers. */
    public String getTaskId() {
        return flowId + ":" + seq;
    }

    public String getFlowId() {
        return flowId;
    }

    /** Seq of the {@code human-review} step's record in the step trace - the {@code resumeFrom} point. */
    public long getSeq() {
        return seq;
    }

    public String getGraphId() {
        return graphId;
    }

    public String getStepId() {
        return stepId;
    }

    public String getQuestion() {
        return question;
    }

    /** What to show the human (document fields, accuracyScore, fileId, ...). */
    public Map<String, Object> getPayload() {
        return payload;
    }

    public ResponseSchema getSchema() {
        return schema;
    }

    /** The accumulated-state key under which the human's answer enters the resumed flow. */
    public String getResumeKey() {
        return resumeKey;
    }

    /** Answer deadline (epoch millis), or {@code null} when unlimited. */
    public Long getDeadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    @Override
    public String toString() {
        return "HumanTask{" + getTaskId() + ", graph=" + graphId + ", step=" + stepId + "}";
    }
}
