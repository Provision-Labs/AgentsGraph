package io.provisionlabs.agentsgraph.interaction;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Ответ человека на {@link HumanTask} - из любого канала (чат, админка, ...). */
public final class HumanTaskDecision {

    private final Map<String, Object> values;
    private final String author;

    public HumanTaskDecision(Map<String, Object> values, String author) {
        this.values = Collections.unmodifiableMap(Objects.requireNonNull(values, "values"));
        this.author = author;
    }

    /** Кнопочный ответ: {@code {"option": "approve"}}. */
    public static HumanTaskDecision option(String option, String author) {
        return new HumanTaskDecision(Map.of(ResponseSchema.OPTION_KEY, option), author);
    }

    /** Значения ответа - целиком лягут в state под {@link HumanTask#getResumeKey()}. */
    public Map<String, Object> getValues() {
        return values;
    }

    public String getAuthor() {
        return author;
    }
}
