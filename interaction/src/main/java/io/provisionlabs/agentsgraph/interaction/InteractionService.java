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
 * The HITL core: turns completed review flows into {@link HumanTask}s and human answers into
 * pipeline continuations. Works ON TOP of the engine's existing machinery, requiring nothing new
 * from it:
 * <ul>
 *   <li>tasks are a {@code TraceStore} projection: flows with status COMPLETED and the
 *       {@link #PENDING_TAG} tag (added by the review branch's terminal edge via tags_to_add)
 *       that don't carry {@link #DONE_TAG} yet; the task body is the saved output of the
 *       {@code human-review} step from the step trace (the step is flagged
 *       {@code "snapshot": true});</li>
 *   <li>an answer is {@code AgentsGraphEngine.resumeFrom(flowId, seq, {resumeKey: answer})}: the
 *       same step restart the admin server uses; human-review sees the answer and the branch
 *       proceeds to post-processing;</li>
 *   <li>idempotency is the {@link #DONE_TAG} on the parent flow: a repeated answer (double click,
 *       second operator) is rejected.</li>
 * </ul>
 */
public final class InteractionService {

    /** Added by the review branch's terminal edge (tags_to_add) - "this flow awaits a human". */
    public static final String PENDING_TAG = "review_pending";
    /** Added by this service on answer/expiry - the task is closed. */
    public static final String DONE_TAG = "review_done";
    /** Added after the task was handed to adapters - publish is not repeated. */
    public static final String PUBLISHED_TAG = "review_published";
    /** Added on top of {@link #DONE_TAG} when the deadline passed. */
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

    /** Open tasks (the operator inbox): review_pending without review_done. */
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

    /** The task with the given id ({@code flowId:seq}), while it is still open. */
    public Optional<HumanTask> pendingTask(String taskId) {
        String flowId = flowIdOf(taskId);
        TraceRecord record = traceStore.find(flowId).orElse(null);
        if (record == null || !record.getTags().contains(PENDING_TAG) || record.getTags().contains(DONE_TAG)) {
            return Optional.empty();
        }
        return taskOf(flowId).filter(task -> task.getTaskId().equals(taskId));
    }

    /**
     * Deliver new tasks to the adapters. Idempotent (the {@link #PUBLISHED_TAG} tag) - call it
     * after each graph run or periodically. Returns the number of tasks delivered.
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
     * A human's answer from any channel: schema validation, double-answer protection, then the
     * pipeline continuation via {@code resumeFrom}. Returns the resumed flow's context.
     *
     * @throws IllegalStateException    the task is already closed (double click / second operator)
     * @throws IllegalArgumentException unknown task, or the answer fails the schema
     */
    public ExecutionContext complete(String taskId, HumanTaskDecision decision) {
        HumanTask task = pendingTask(taskId).orElseThrow(() -> {
            boolean known = traceStore.find(flowIdOf(taskId)).isPresent();
            return known
                    ? new IllegalStateException("Task '" + taskId + "' is already closed")
                    : new IllegalArgumentException("Unknown task '" + taskId + "'");
        });
        task.getSchema().validate(decision.getValues());

        // The tag goes on BEFORE the resume: a failed resumeFrom is better handled manually (the
        // task is findable as review_done without a child flow) than allowing two parallel
        // resumes of the same answer.
        traceStore.addTags(task.getFlowId(), Set.of(DONE_TAG));
        log.info("Task {} completed by {}", taskId, decision.getAuthor());

        ExecutionContext resumed = engine.resumeFrom(task.getFlowId(), task.getSeq(),
                Map.of(task.getResumeKey(), decision.getValues()));
        notifyClosed(task, HumanTaskAdapter.TaskOutcome.COMPLETED);
        return resumed;
    }

    /** Closes overdue tasks (deadline from the step's {@code timeoutSeconds}). Call from a scheduler. */
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

    // -- Task projection over the trace -----------------------------------------------------------

    /** The latest step record with {@code humanTask} in its output - task body + resume coordinates. */
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
        List<String> options = stringList(body.get("options"));
        List<String> requiredKeys = stringList(body.get("requiredKeys"));
        if (!options.isEmpty()) {
            schema = ResponseSchema.ofOptions(options);
        } else if (!requiredKeys.isEmpty()) {
            schema = ResponseSchema.ofRequiredKeys(requiredKeys);
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

    private static List<String> stringList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            result.add(String.valueOf(item));
        }
        return result;
    }
}
