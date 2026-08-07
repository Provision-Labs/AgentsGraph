package io.provisionlabs.agentsgraph.interaction;

import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The HITL branch step. A pure processor with no notion of "pausing" - two modes decided by the
 * context data:
 * <ul>
 *   <li>the human's answer ({@code resumeKey}) is NOT in state - first run: builds the task
 *       payload from the context and returns {@code humanTask} + {@code reviewPending=true};
 *       node routing takes the flow to the terminal branch (an edge with
 *       {@code tags_to_add: ["review_pending"]}), the flow completes NORMALLY, and the task shows
 *       up in {@link InteractionService#pending()};</li>
 *   <li>the answer IS present (we arrived here via {@code resumeFrom} with overrides) - passes it
 *       through: {@code {resumeKey: answer, reviewPending: false}}; the edge continues to
 *       apply-corrections and routing proceeds to post-processing.</li>
 * </ul>
 *
 * <p>The step MUST be flagged {@code "snapshot": true} in the graph - otherwise production runs
 * leave no record for {@code resumeFrom}.
 *
 * <p>Processor/step params: {@code question}; {@code showKeys} - context keys copied into the
 * payload (csv or list); {@code options} - button answer variants (csv); {@code requiredKeys} -
 * required keys of a form answer; {@code resumeKey} (default {@code humanReview});
 * {@code timeoutSeconds} - answer deadline.
 */
public final class HumanReviewProcessor implements Processor {

    public static final String DEFAULT_RESUME_KEY = "humanReview";

    /** Output key holding the task body - read by {@link InteractionService} from the step trace. */
    public static final String TASK_OUTPUT_KEY = "humanTask";
    /** Output key for node routing: true = waiting for a human, false = answer received. */
    public static final String PENDING_OUTPUT_KEY = "reviewPending";

    private Map<String, Object> params = Map.of();

    @Override
    public void init(Map<String, Object> params) {
        this.params = params == null ? Map.of() : params;
    }

    @Override
    public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
        Map<String, Object> effective = new LinkedHashMap<>(params);
        effective.putAll(step.getParams());
        String resumeKey = stringParam(effective, "resumeKey", DEFAULT_RESUME_KEY);

        Object answer = context.getAccumulatedState().get(resumeKey);
        if (answer != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(resumeKey, answer);
            out.put(PENDING_OUTPUT_KEY, false);
            return out;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        for (String key : listParam(effective, "showKeys")) {
            Object value = context.getAccumulatedState().containsKey(key)
                    ? context.getAccumulatedState().get(key)
                    : context.getInputData().get(key);
            if (value != null) {
                payload.put(key, value);
            }
        }

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("question", stringParam(effective, "question", "Please review the data"));
        task.put("payload", payload);
        task.put("resumeKey", resumeKey);
        List<String> options = listParam(effective, "options");
        if (!options.isEmpty()) {
            task.put("options", options);
        }
        List<String> requiredKeys = listParam(effective, "requiredKeys");
        if (!requiredKeys.isEmpty()) {
            task.put("requiredKeys", requiredKeys);
        }
        long timeoutSeconds = longParam(effective, "timeoutSeconds", 0);
        if (timeoutSeconds > 0) {
            task.put("deadlineEpochMillis", System.currentTimeMillis() + timeoutSeconds * 1000);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(TASK_OUTPUT_KEY, task);
        out.put(PENDING_OUTPUT_KEY, true);
        return out;
    }

    private static String stringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private static long longParam(Map<String, Object> params, String key, long defaultValue) {
        Object value = params.get(key);
        return value == null ? defaultValue : Long.parseLong(String.valueOf(value).trim());
    }

    @SuppressWarnings("unchecked")
    private static List<String> listParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List) {
            List<String> result = new ArrayList<>();
            ((List<Object>) value).forEach(item -> result.add(String.valueOf(item)));
            return result;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(result::add);
        return result;
    }
}
