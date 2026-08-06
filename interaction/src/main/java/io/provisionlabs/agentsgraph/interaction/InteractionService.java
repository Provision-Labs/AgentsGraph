package io.provisionlabs.agentsgraph.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import io.provisionlabs.agentsgraph.trace.TraceRecord;
import io.provisionlabs.agentsgraph.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ядро HITL: превращает завершённые review-flow в {@link HumanTask} и ответы людей - в
 * продолжение пайплайна. Работает ПОВЕРХ существующих механизмов движка, ничего в нём не требуя:
 * <ul>
 *   <li>задачи - проекция {@code TraceStore}: flow со статусом COMPLETED и тегом
 *       {@link #PENDING_TAG} (его вешает терминальный edge review-ветки, tags_to_add), у которых
 *       ещё нет тега {@link #DONE_TAG}; тело задачи - сохранённый output шага
 *       {@code human-review} из step-трейса (шаг помечен {@code "snapshot": true});</li>
 *   <li>ответ - {@code AgentsGraphEngine.resumeFrom(flowId, seq, {resumeKey: ответ})}: тот же
 *       рестарт шага, что в admin-server; human-review видит ответ и ветка едет в пост-обработку;</li>
 *   <li>идемпотентность - тег {@link #DONE_TAG} на родительском flow: повторный ответ (двойной
 *       клик, второй оператор) отвергается.</li>
 * </ul>
 */
public final class InteractionService {

    /** Вешается терминальным edge'м review-ветки (tags_to_add) - "flow ждёт человека". */
    public static final String PENDING_TAG = "review_pending";
    /** Вешается этим сервисом при ответе/истечении - задача закрыта. */
    public static final String DONE_TAG = "review_done";
    /** Вешается после доставки задачи адаптерам - повторный publish не делается. */
    public static final String PUBLISHED_TAG = "review_published";
    /** Дополнительно к {@link #DONE_TAG} при истечении дедлайна. */
    public static final String EXPIRED_TAG = "review_expired";

    private static final Logger log = LoggerFactory.getLogger(InteractionService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentsGraphEngine engine;
    private final TraceStore traceStore;
    private final List<HumanTaskAdapter> adapters;

    public InteractionService(AgentsGraphEngine engine, List<HumanTaskAdapter> adapters) {
        this.engine = engine;
        this.traceStore = engine.getTraceStore();
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
    }

    /** Открытые задачи (инбокс оператора): review_pending без review_done. */
    public List<HumanTask> pending() {
        List<HumanTask> tasks = new ArrayList<>();
        for (TraceRecord record : traceStore.query(Set.of(PENDING_TAG), ExecutionStatus.COMPLETED, null)) {
            if (record.getTags().contains(DONE_TAG)) {
                continue;
            }
            taskOf(record.getFlowId()).ifPresent(tasks::add);
        }
        return tasks;
    }

    /** Задача по id ({@code flowId:seq}), если она ещё открыта. */
    public Optional<HumanTask> pendingTask(String taskId) {
        String flowId = flowIdOf(taskId);
        TraceRecord record = traceStore.find(flowId).orElse(null);
        if (record == null || !record.getTags().contains(PENDING_TAG) || record.getTags().contains(DONE_TAG)) {
            return Optional.empty();
        }
        return taskOf(flowId).filter(task -> task.getTaskId().equals(taskId));
    }

    /**
     * Доставить новые задачи адаптерам. Идемпотентен (тег {@link #PUBLISHED_TAG}) - зовите после
     * каждого прогона графа или периодически. Возвращает число доставленных задач.
     */
    public int publishNew() {
        int published = 0;
        for (TraceRecord record : traceStore.query(Set.of(PENDING_TAG), ExecutionStatus.COMPLETED, null)) {
            if (record.getTags().contains(DONE_TAG) || record.getTags().contains(PUBLISHED_TAG)) {
                continue;
            }
            HumanTask task = taskOf(record.getFlowId()).orElse(null);
            if (task == null) {
                continue;
            }
            traceStore.addTags(record.getFlowId(), Set.of(PUBLISHED_TAG));
            for (HumanTaskAdapter adapter : adapters) {
                if (adapter.supports(task)) {
                    try {
                        adapter.publish(task);
                    } catch (RuntimeException e) {
                        log.error("Adapter {} failed to publish {}", adapter.getClass().getSimpleName(), task, e);
                    }
                }
            }
            published++;
        }
        return published;
    }

    /**
     * Ответ человека из любого канала: валидация по схеме, защита от повторного ответа, затем
     * продолжение пайплайна через {@code resumeFrom}. Возвращает контекст резюмированного flow.
     *
     * @throws IllegalStateException    задача уже закрыта (двойной клик / второй оператор)
     * @throws IllegalArgumentException неизвестная задача или ответ не проходит схему
     */
    public ExecutionContext complete(String taskId, HumanTaskDecision decision) {
        HumanTask task = pendingTask(taskId).orElseThrow(() -> {
            boolean known = traceStore.find(flowIdOf(taskId)).isPresent();
            return known
                    ? new IllegalStateException("Task '" + taskId + "' is already closed")
                    : new IllegalArgumentException("Unknown task '" + taskId + "'");
        });
        task.getSchema().validate(decision.getValues());

        // Тег ставится ДО резюма: упавший resumeFrom лучше разбирать вручную (задача видна по
        // review_done без дочернего flow), чем допустить два параллельных резюма одного ответа.
        traceStore.addTags(task.getFlowId(), Set.of(DONE_TAG));
        log.info("Task {} completed by {}", taskId, decision.getAuthor());

        ExecutionContext resumed = engine.resumeFrom(task.getFlowId(), task.getSeq(),
                Map.of(task.getResumeKey(), decision.getValues()));
        notifyClosed(task, HumanTaskAdapter.TaskOutcome.COMPLETED);
        return resumed;
    }

    /** Закрывает просроченные задачи (дедлайн из {@code timeoutSeconds} шага). Зовётся планировщиком. */
    public int expireOverdue() {
        long now = System.currentTimeMillis();
        int expired = 0;
        for (HumanTask task : pending()) {
            if (task.getDeadlineEpochMillis() == null || task.getDeadlineEpochMillis() > now) {
                continue;
            }
            traceStore.addTags(task.getFlowId(), Set.of(DONE_TAG, EXPIRED_TAG));
            log.warn("Task {} expired (deadline {})", task.getTaskId(), task.getDeadlineEpochMillis());
            notifyClosed(task, HumanTaskAdapter.TaskOutcome.EXPIRED);
            expired++;
        }
        return expired;
    }

    // -- Проекция задачи из трейса ----------------------------------------------------------------

    /** Последняя запись шага с {@code humanTask} в output - тело задачи + координаты резюма. */
    private Optional<HumanTask> taskOf(String flowId) {
        List<StepTraceRecord> steps = engine.getStepTraces(flowId);
        for (int i = steps.size() - 1; i >= 0; i--) {
            StepTraceRecord step = steps.get(i);
            Map<String, Object> output = parseJsonMap(step.getOutputJson());
            Object taskBody = output.get(HumanReviewProcessor.TASK_OUTPUT_KEY);
            if (taskBody instanceof Map) {
                return Optional.of(toTask(flowId, step, asMap(taskBody)));
            }
        }
        log.warn("Flow '{}' is tagged {} but has no recorded human-review step - is the step"
                + " marked \"snapshot\": true in the graph?", flowId, PENDING_TAG);
        return Optional.empty();
    }

    private HumanTask toTask(String flowId, StepTraceRecord step, Map<String, Object> body) {
        ResponseSchema schema;
        if (body.get("options") instanceof List<?> options && !options.isEmpty()) {
            schema = ResponseSchema.ofOptions(options.stream().map(String::valueOf).toList());
        } else if (body.get("requiredKeys") instanceof List<?> keys && !keys.isEmpty()) {
            schema = ResponseSchema.ofRequiredKeys(keys.stream().map(String::valueOf).toList());
        } else {
            schema = ResponseSchema.freeForm();
        }
        Object deadline = body.get("deadlineEpochMillis");
        return new HumanTask(flowId, step.getSeq(), step.getGraphId(), step.getStepId(),
                String.valueOf(body.getOrDefault("question", "")),
                asMap(body.getOrDefault("payload", Map.of())),
                schema,
                (String) body.get("resumeKey"),
                deadline == null ? null : Long.valueOf(String.valueOf(deadline)));
    }

    private void notifyClosed(HumanTask task, HumanTaskAdapter.TaskOutcome outcome) {
        for (HumanTaskAdapter adapter : adapters) {
            if (adapter.supports(task)) {
                try {
                    adapter.closed(task, outcome);
                } catch (RuntimeException e) {
                    log.warn("Adapter {} failed on closed({})", adapter.getClass().getSimpleName(), task, e);
                }
            }
        }
    }

    private static String flowIdOf(String taskId) {
        int separator = taskId.lastIndexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("Bad task id '" + taskId + "', expected flowId:seq");
        }
        return taskId.substring(0, separator);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
