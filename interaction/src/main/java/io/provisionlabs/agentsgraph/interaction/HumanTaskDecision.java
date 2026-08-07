package io.provisionlabs.agentsgraph.interaction;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** A human's answer to a {@link HumanTask} - from any channel (chat, admin inbox, ...). */
public final class HumanTaskDecision {

    private final Map<String, Object> values;
    private final String author;

    public HumanTaskDecision(Map<String, Object> values, String author) {
        this.values = Collections.unmodifiableMap(Objects.requireNonNull(values, "values"));
        this.author = author;
    }

    /** A button answer: {@code {"option": "approve"}}. */
    public static HumanTaskDecision option(String option, String author) {
        return new HumanTaskDecision(Map.of(ResponseSchema.OPTION_KEY, option), author);
    }

    /** The answer values - stored wholesale in state under {@link HumanTask#getResumeKey()}. */
    public Map<String, Object> getValues() {
        return values;
    }

    public String getAuthor() {
        return author;
    }
}
