package io.provisionlabs.agentsgraph.interaction;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Задача для человека, построенная из ЗАВЕРШЁННОГО flow, ушедшего в review-ветку: сохранённый
 * output шага {@code human-review} (см. {@link HumanReviewProcessor}) плюс координаты для
 * продолжения ({@code AgentsGraphEngine.resumeFrom(flowId, seq, ...)}). Движок про задачи ничего
 * не знает - это проекция interaction-модуля поверх {@code TraceStore}.
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

    /** Естественный ключ задачи - он же ключ идемпотентности ответа. */
    public String getTaskId() {
        return flowId + ":" + seq;
    }

    public String getFlowId() {
        return flowId;
    }

    /** Seq записи шага {@code human-review} в step-трейсе - точка {@code resumeFrom}. */
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

    /** Что показать человеку (поля документа, accuracyScore, fileId, ...). */
    public Map<String, Object> getPayload() {
        return payload;
    }

    public ResponseSchema getSchema() {
        return schema;
    }

    /** Ключ accumulated state, под которым ответ человека уедет в резюмированный flow. */
    public String getResumeKey() {
        return resumeKey;
    }

    /** Дедлайн ответа (epoch millis) или {@code null}, если не ограничен. */
    public Long getDeadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    @Override
    public String toString() {
        return "HumanTask{" + getTaskId() + ", graph=" + graphId + ", step=" + stepId + "}";
    }
}
