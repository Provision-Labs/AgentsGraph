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
 * Шаг HITL-ветки. Чистый процессор без знания о "паузах" - два режима по данным контекста:
 * <ul>
 *   <li>ответа человека ({@code resumeKey}) в state НЕТ - первый прогон: собирает payload задачи
 *       из контекста и возвращает {@code humanTask} + {@code reviewPending=true}; роутинг ноды
 *       уводит flow в терминальную ветку (edge с {@code tags_to_add: ["review_pending"]}), flow
 *       ШТАТНО завершается, задача видна {@link InteractionService#pending()};</li>
 *   <li>ответ ЕСТЬ (мы пришли сюда {@code resumeFrom} с overrides) - пропускает его дальше:
 *       {@code {resumeKey: ответ, reviewPending: false}}, edge доезжает до apply-corrections и
 *       роутинг ведёт в пост-обработку.</li>
 * </ul>
 *
 * <p>Шаг ОБЯЗАН быть помечен {@code "snapshot": true} в графе - иначе в проде не будет записи
 * для {@code resumeFrom}.
 *
 * <p>Параметры процессора/шага: {@code question}; {@code showKeys} - какие ключи контекста
 * скопировать в payload (csv или список); {@code options} - варианты кнопочного ответа (csv);
 * {@code requiredKeys} - обязательные ключи ответа-формы; {@code resumeKey} (default
 * {@code humanReview}); {@code timeoutSeconds} - дедлайн ответа.
 */
public final class HumanReviewProcessor implements Processor {

    public static final String DEFAULT_RESUME_KEY = "humanReview";

    /** Ключ output с телом задачи - его читает {@link InteractionService} из step-трейса. */
    public static final String TASK_OUTPUT_KEY = "humanTask";
    /** Ключ output для роутинга ноды: true = ждём человека, false = ответ получен. */
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
        task.put("question", stringParam(effective, "question", "Проверьте данные"));
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
