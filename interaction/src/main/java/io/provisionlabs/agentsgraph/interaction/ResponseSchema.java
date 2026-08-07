package io.provisionlabs.agentsgraph.interaction;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What counts as a valid human answer - checked ONCE in {@link InteractionService#complete} for
 * every delivery channel; adapters submit raw answers.
 *
 * <p>Two mutually exclusive forms:
 * <ul>
 *   <li>{@code options} - pick one: the answer must contain
 *       {@code {"option": "<one of options>"}} (approve/reject buttons);</li>
 *   <li>{@code requiredKeys} - a form: the answer must contain every listed key
 *       (corrected document fields);</li>
 *   <li>both empty - free form, any non-empty answer.</li>
 * </ul>
 */
public final class ResponseSchema {

    public static final String OPTION_KEY = "option";

    private final List<String> options;
    private final List<String> requiredKeys;

    private ResponseSchema(List<String> options, List<String> requiredKeys) {
        this.options = options == null ? List.of() : Collections.unmodifiableList(options);
        this.requiredKeys = requiredKeys == null ? List.of() : Collections.unmodifiableList(requiredKeys);
    }

    public static ResponseSchema ofOptions(List<String> options) {
        return new ResponseSchema(options, null);
    }

    public static ResponseSchema ofRequiredKeys(List<String> requiredKeys) {
        return new ResponseSchema(null, requiredKeys);
    }

    public static ResponseSchema freeForm() {
        return new ResponseSchema(null, null);
    }

    public List<String> getOptions() {
        return options;
    }

    public List<String> getRequiredKeys() {
        return requiredKeys;
    }

    /** @throws IllegalArgumentException when the answer does not pass this schema */
    public void validate(Map<String, Object> answer) {
        if (answer == null || answer.isEmpty()) {
            throw new IllegalArgumentException("Empty answer");
        }
        if (!options.isEmpty()) {
            Object option = answer.get(OPTION_KEY);
            if (option == null || !options.contains(String.valueOf(option))) {
                throw new IllegalArgumentException(
                        "Answer '" + OPTION_KEY + "' must be one of " + options + ", got: " + option);
            }
            return;
        }
        for (String key : requiredKeys) {
            if (!answer.containsKey(key)) {
                throw new IllegalArgumentException("Answer misses required key '" + key + "'");
            }
        }
    }
}
