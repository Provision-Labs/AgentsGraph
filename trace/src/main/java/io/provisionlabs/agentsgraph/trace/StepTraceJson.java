package io.provisionlabs.agentsgraph.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * (De)serializes a flow's step-level debug trace as a JSON document - the portable form of a
 * debug run: attach it to a bug report, archive it, or feed it back into the
 * {@code agentsgraph-test} harness as a replay fixture ({@code harness.mocksFromDump}) so the
 * failing flow re-runs anywhere with the recorded external-service answers and zero network.
 *
 * <p>The document is simply the JSON array of {@link StepTraceRecord}s in execution order; each
 * record already carries its own context/output snapshots as embedded JSON strings (see
 * {@link ContextJsonCodec}), so the dump is self-contained.
 */
public final class StepTraceJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StepTraceJson() {
    }

    /** Pretty-printed JSON array of {@code records}, in the given (execution) order. */
    public static String toJson(List<StepTraceRecord> records) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(records);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize step trace dump", e);
        }
    }

    /** Reads a {@link #toJson} document back into records (execution order preserved). */
    public static List<StepTraceRecord> fromJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<StepTraceRecord>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse step trace dump", e);
        }
    }
}
