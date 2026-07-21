package io.provisionlabs.agentsgraph.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Serializes {@link ExecutionContext} snapshots (and raw step output maps) to JSON for the
 * step-level debug trace, and reads them back for {@code resumeFrom}.
 *
 * <p>Values are handled defensively, per entry - one awkward value must not lose the whole
 * snapshot:
 * <ul>
 *   <li>{@code byte[]} is wrapped as {@code {"__bytes__": "<base64>"}} and decoded back to
 *       {@code byte[]} on read, so file payloads survive a resume round-trip;</li>
 *   <li>a value Jackson can't serialize is replaced with
 *       {@code {"__unserializable__": "<class>"}} and the snapshot is marked
 *       non-restartable;</li>
 *   <li>a value whose JSON exceeds {@link #getMaxValueBytes()} is replaced with
 *       {@code {"__truncated__": <size>}} and the snapshot is marked non-restartable.</li>
 * </ul>
 *
 * <p>Type-fidelity caveat: POJO values serialize fine but come back as {@code Map}s on read (and
 * longs may come back as ints when small) - steps that exchange plain JSON-like data (maps,
 * lists, strings, numbers, booleans, {@code byte[]}) round-trip exactly; steps that put rich
 * POJOs into the context are traceable but a resume hands their downstream a {@code Map}.
 */
public final class ContextJsonCodec {

    public static final String BYTES_MARKER = "__bytes__";
    public static final String UNSERIALIZABLE_MARKER = "__unserializable__";
    public static final String TRUNCATED_MARKER = "__truncated__";
    public static final int DEFAULT_MAX_VALUE_BYTES = 1_048_576;

    private static final String INPUT_DATA = "input_data";
    private static final String ACCUMULATED_STATE = "accumulated_state";
    private static final String METADATA = "metadata";

    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxValueBytes;

    public ContextJsonCodec() {
        this(DEFAULT_MAX_VALUE_BYTES);
    }

    public ContextJsonCodec(int maxValueBytes) {
        this.maxValueBytes = maxValueBytes;
    }

    public int getMaxValueBytes() {
        return maxValueBytes;
    }

    /** JSON + whether every value survived intact (i.e. the snapshot can seed a resume). */
    public static final class Snapshot {
        private final String json;
        private final boolean restartable;

        Snapshot(String json, boolean restartable) {
            this.json = json;
            this.restartable = restartable;
        }

        public String getJson() {
            return json;
        }

        public boolean isRestartable() {
            return restartable;
        }
    }

    /** The three context sections of a decoded snapshot. */
    public static final class DecodedContext {
        private final Map<String, Object> inputData;
        private final Map<String, Object> accumulatedState;
        private final Map<String, Object> metadata;

        DecodedContext(Map<String, Object> inputData, Map<String, Object> accumulatedState,
                        Map<String, Object> metadata) {
            this.inputData = inputData;
            this.accumulatedState = accumulatedState;
            this.metadata = metadata;
        }

        public Map<String, Object> getInputData() {
            return inputData;
        }

        public Map<String, Object> getAccumulatedState() {
            return accumulatedState;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }

    /** Serializes the full context (input_data + accumulated_state + metadata). */
    public Snapshot snapshotContext(ExecutionContext context) {
        boolean[] restartable = {true};
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(INPUT_DATA, sanitizeMap(context.getInputData(), restartable));
        root.put(ACCUMULATED_STATE, sanitizeMap(context.getAccumulatedState(), restartable));
        root.put(METADATA, sanitizeMap(context.getMetadata(), restartable));
        return new Snapshot(writeSanitized(root), restartable[0]);
    }

    /** Serializes a plain map (e.g. a step's raw output). */
    public Snapshot snapshotMap(Map<String, Object> map) {
        boolean[] restartable = {true};
        Map<String, Object> sanitized = sanitizeMap(map == null ? Map.of() : map, restartable);
        return new Snapshot(writeSanitized(sanitized), restartable[0]);
    }

    /** Reads a {@link #snapshotContext} JSON back into its three sections. */
    public DecodedContext readContext(String json) {
        Map<String, Object> root = parse(json);
        return new DecodedContext(
                decodeMap(root.get(INPUT_DATA)),
                decodeMap(root.get(ACCUMULATED_STATE)),
                decodeMap(root.get(METADATA)));
    }

    /** Reads a {@link #snapshotMap} JSON back into a map. */
    public Map<String, Object> readMap(String json) {
        return decodeMap(parse(json));
    }

    private Map<String, Object> parse(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = mapper.readValue(json, Map.class);
            return root;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse context snapshot JSON", e);
        }
    }

    private String writeSanitized(Object sanitized) {
        try {
            return mapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException e) {
            // Every value already survived a per-entry writeValueAsString in sanitizeValue.
            throw new IllegalStateException("Failed to serialize sanitized context snapshot", e);
        }
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source, boolean[] restartable) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            sanitized.put(entry.getKey(), sanitizeValue(entry.getValue(), restartable));
        }
        return sanitized;
    }

    private Object sanitizeValue(Object value, boolean[] restartable) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            if (bytes.length > maxValueBytes) {
                restartable[0] = false;
                return Map.of(TRUNCATED_MARKER, bytes.length);
            }
            return Map.of(BYTES_MARKER, Base64.getEncoder().encodeToString(bytes));
        }
        try {
            String json = mapper.writeValueAsString(value);
            if (json.length() > maxValueBytes) {
                restartable[0] = false;
                return Map.of(TRUNCATED_MARKER, json.length());
            }
            // Normalize to plain maps/lists/scalars so the whole snapshot re-serializes safely.
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            restartable[0] = false;
            return Map.of(UNSERIALIZABLE_MARKER, value.getClass().getName());
        }
    }

    private Map<String, Object> decodeMap(Object raw) {
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<String, Object> decoded = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) raw;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            decoded.put(entry.getKey(), decodeValue(entry.getValue()));
        }
        return decoded;
    }

    private Object decodeValue(Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.size() == 1 && map.containsKey(BYTES_MARKER)) {
                return Base64.getDecoder().decode(String.valueOf(map.get(BYTES_MARKER)));
            }
            return decodeMap(map);
        }
        if (value instanceof List) {
            return ((List<?>) value).stream().map(this::decodeValue).collect(Collectors.toList());
        }
        return value;
    }
}
