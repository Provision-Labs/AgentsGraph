package io.provisionlabs.agentsgraph.config.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.provisionlabs.agentsgraph.config.ProcessorDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts a {@link ProcessorDefinition} to/from JSON ({@link ProcessorDefinitionJson}), e.g. for
 * a {@code processor_registry} config file or a database {@code params} column.
 */
public final class ProcessorJsonMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ProcessorJsonMapper() {
    }

    public static List<ProcessorDefinition> fromJsonArray(String processorsJsonArray) {
        List<ProcessorDefinitionJson> parsed = readValue(
                processorsJsonArray, new TypeReference<List<ProcessorDefinitionJson>>() { });
        List<ProcessorDefinition> result = new ArrayList<>();
        for (ProcessorDefinitionJson json : parsed) {
            result.add(toProcessorDefinition(json));
        }
        return result;
    }

    public static ProcessorDefinition toProcessorDefinition(ProcessorDefinitionJson json) {
        return new ProcessorDefinition(json.id, json.name, json.isExternal, json.instanceClass, paramsOf(json));
    }

    /** {@code params} may arrive as a nested JSON object or as a raw JSON string (legacy DB column). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(ProcessorDefinitionJson json) {
        if (json.params == null) {
            return Map.of();
        }
        if (json.params instanceof String) {
            String raw = ((String) json.params).trim();
            if (raw.isEmpty()) {
                return Map.of();
            }
            return readValue(raw, new TypeReference<Map<String, Object>>() { });
        }
        if (json.params instanceof Map) {
            return (Map<String, Object>) json.params;
        }
        throw new IllegalArgumentException("Unsupported params type: " + json.params.getClass());
    }

    public static String toJson(ProcessorDefinition definition) {
        ProcessorDefinitionJson json = new ProcessorDefinitionJson();
        json.id = definition.getId();
        json.name = definition.getName();
        json.isExternal = definition.isExternal();
        json.instanceClass = definition.getInstanceClass();
        json.params = definition.getParams();
        return writeValue(json);
    }

    /** Serializes a processor's {@code params} map alone, e.g. for a DB {@code TEXT} column. */
    public static String toParamsJson(Map<String, Object> params) {
        return writeValue(params == null ? Map.of() : params);
    }

    /** Inverse of {@link #toParamsJson}; treats {@code null}/blank input as an empty map. */
    public static Map<String, Object> fromParamsJson(String paramsJson) {
        if (paramsJson == null || paramsJson.trim().isEmpty()) {
            return Map.of();
        }
        return readValue(paramsJson, new TypeReference<Map<String, Object>>() { });
    }

    private static <T> T readValue(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON as " + type.getType(), e);
        }
    }

    private static String writeValue(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize " + value.getClass(), e);
        }
    }
}
