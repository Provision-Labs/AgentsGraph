package io.provisionlabs.agentsgraph.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholders in processor and step {@code params}: {@code ${{NAME}}} and {@code ${{NAME:default}}}, e.g.
 * {@code "processUrl": "${{OCR_SERVER:http://10.64.0.39:8191}}/processing/{template}"}. A deployment then points
 * its graphs at another OCR or LLM server with an environment variable instead of rewriting
 * {@code agentsgraph_processor} rows.
 *
 * <p>The value of {@code NAME} is the first non-blank value among the {@link Source sources}, in order (the
 * default set: environment variables of the process/container, then JVM system properties; the {@code spring}
 * module adds the application's settings); otherwise the {@code default} after the first colon; otherwise the
 * placeholder is unresolved and {@link #resolve} throws {@link UnresolvedPlaceholderException} - a processor row
 * then fails to load (isolated to that row by {@code ProcessorLoader}), a step fails like any step failure.
 *
 * <p>Double braces keep the syntax apart from Spring's {@code ${...}}, which processors already use in
 * {@code @Value} fields, and from single-brace templates such as {@code {template}}, which stay untouched.
 * Values are resolved in every string of the params, nested maps and lists included; other values pass as is.
 */
public final class Placeholders {

    /** Origin of a value: an environment variable. */
    public static final String ENVIRONMENT = "environment";
    /** Origin of a value: a JVM system property. */
    public static final String SYSTEM = "system";
    /** Origin of a value: the default written in the placeholder itself. */
    public static final String DEFAULT = "default";
    /** No source and no default: the placeholder does not resolve. */
    public static final String MISSING = "missing";

    /** Start of every placeholder - the cheap check before the pattern runs. */
    public static final String MARK = "${{";

    private static final Pattern PATTERN = Pattern.compile("\\$\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*(?::(.*?))?\\}\\}");

    private final List<Source> sources;

    public Placeholders(List<Source> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** Environment variables, then JVM system properties - the library's default without a DI container. */
    public static Placeholders environment() {
        return new Placeholders(List.of(Source.environment(), Source.systemProperties()));
    }

    public List<Source> getSources() {
        return sources;
    }

    /** Value of {@code name}: the first non-blank source value, else {@code defaultValue}, else missing. */
    public Value lookup(String name, String defaultValue) {
        for (Source source : sources) {
            String value;
            try {
                value = source.lookup.apply(name);
            } catch (RuntimeException e) {
                value = null;                                   // a source that cannot answer counts as empty
            }
            if (value != null && !value.isBlank()) {
                return new Value(name, value, source.origin, defaultValue);
            }
        }
        if (defaultValue != null) {
            return new Value(name, defaultValue, DEFAULT, defaultValue);
        }
        return new Value(name, null, MISSING, null);
    }

    /** {@code text} with every placeholder replaced; text without placeholders is returned as is. */
    public String resolve(String text) {
        if (text == null || !text.contains(MARK)) {
            return text;
        }
        Matcher matcher = PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Value value = lookup(matcher.group(1), matcher.group(2));
            if (value.getValue() == null) {
                throw new UnresolvedPlaceholderException(value.getName());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value.getValue()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Strings resolved, maps and lists walked, anything else returned as is. */
    public Object resolveValue(Object value) {
        if (value instanceof String) {
            return resolve((String) value);
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(e.getKey()), resolveValue(e.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                out.add(resolveValue(item));
            }
            return out;
        }
        return value;
    }

    /** Params with placeholders resolved; the same map when there is nothing to resolve. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveParams(Map<String, Object> params) {
        if (params == null || !contains(params)) {
            return params;
        }
        return (Map<String, Object>) resolveValue(params);
    }

    /** The step with its params resolved; the same step when they contain no placeholder. */
    public StepDefinition resolveStep(StepDefinition step) {
        if (step == null || !contains(step.getParams())) {
            return step;
        }
        return new StepDefinition(step.getId(), step.getProcessorRef(), resolveParams(step.getParams()),
                step.getOutputToNext(), step.getOutputToSave(), step.isSnapshot());
    }

    /**
     * Every placeholder used in {@code value} (strings, nested maps and lists), once per name, with what it
     * resolves to and where the value came from - for an admin screen next to the raw params.
     */
    public List<Value> describe(Object value) {
        Map<String, Value> found = new LinkedHashMap<>();
        collect(value, found);
        return Collections.unmodifiableList(new ArrayList<>(found.values()));
    }

    private void collect(Object value, Map<String, Value> found) {
        if (value instanceof String) {
            String text = (String) value;
            if (!text.contains(MARK)) {
                return;
            }
            Matcher matcher = PATTERN.matcher(text);
            while (matcher.find()) {
                found.putIfAbsent(matcher.group(1), lookup(matcher.group(1), matcher.group(2)));
            }
        } else if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                collect(item, found);
            }
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                collect(item, found);
            }
        }
    }

    /** Whether {@code value} (a string, or a map / list holding one) contains a placeholder. */
    public static boolean contains(Object value) {
        if (value instanceof String) {
            return ((String) value).contains(MARK) && PATTERN.matcher((String) value).find();
        }
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                if (contains(item)) {
                    return true;
                }
            }
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (contains(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Where values come from: an origin label (shown to admins) and a lookup returning null when absent. */
    public static final class Source {

        private final String origin;
        private final Function<String, String> lookup;

        public Source(String origin, Function<String, String> lookup) {
            this.origin = Objects.requireNonNull(origin, "origin");
            this.lookup = Objects.requireNonNull(lookup, "lookup");
        }

        public static Source environment() {
            return new Source(ENVIRONMENT, System::getenv);
        }

        public static Source systemProperties() {
            return new Source(SYSTEM, System::getProperty);
        }

        public String getOrigin() {
            return origin;
        }
    }

    /** One placeholder with its value ({@code null} when missing), origin and the default written in it. */
    public static final class Value {

        private final String name;
        private final String value;
        private final String origin;
        private final String defaultValue;

        Value(String name, String value, String origin, String defaultValue) {
            this.name = name;
            this.value = value;
            this.origin = origin;
            this.defaultValue = defaultValue;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        /** {@link #ENVIRONMENT}, {@link #SYSTEM}, {@link #DEFAULT}, {@link #MISSING} or a source's own label. */
        public String getOrigin() {
            return origin;
        }

        public String getDefaultValue() {
            return defaultValue;
        }
    }

    /** A placeholder with no value in any source and no default. */
    public static final class UnresolvedPlaceholderException extends IllegalStateException {

        private final String name;

        public UnresolvedPlaceholderException(String name) {
            super("Placeholder ${{" + name + "}} has no value: set the environment variable " + name
                    + " or write a default as ${{" + name + ":value}}");
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
