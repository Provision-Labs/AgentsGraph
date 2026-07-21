package io.provisionlabs.agentsgraph.trace;

import io.provisionlabs.agentsgraph.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextJsonCodecTest {

    private final ContextJsonCodec codec = new ContextJsonCodec();

    @Test
    void roundTripsPlainJsonLikeContextExactly() {
        ExecutionContext context = ExecutionContext
                .newFlow(Map.of("hasFile", true, "templateTag", "accountant"), Map.of("tenant_id", "t1"))
                .withMergedState(Map.of("documentType", "invoice", "confidence", 0.95,
                        "items", List.of("a", "b")));

        ContextJsonCodec.Snapshot snapshot = codec.snapshotContext(context);
        assertThat(snapshot.isRestartable()).isTrue();

        ContextJsonCodec.DecodedContext decoded = codec.readContext(snapshot.getJson());
        assertThat(decoded.getInputData())
                .containsEntry("hasFile", true)
                .containsEntry("templateTag", "accountant");
        assertThat(decoded.getAccumulatedState())
                .containsEntry("documentType", "invoice")
                .containsEntry("confidence", 0.95)
                .containsEntry("items", List.of("a", "b"));
        assertThat(decoded.getMetadata()).containsEntry("tenant_id", "t1");
    }

    @Test
    void byteArraysSurviveTheRoundTripAsByteArrays() {
        byte[] fileBytes = {1, 2, 3, 4, 5};
        ExecutionContext context = ExecutionContext.newFlow(Map.of("fileBytes", fileBytes), Map.of());

        ContextJsonCodec.Snapshot snapshot = codec.snapshotContext(context);
        assertThat(snapshot.isRestartable()).isTrue();

        ContextJsonCodec.DecodedContext decoded = codec.readContext(snapshot.getJson());
        assertThat(decoded.getInputData().get("fileBytes")).isEqualTo(fileBytes);
    }

    @Test
    void unserializableValueBecomesPlaceholderAndMarksSnapshotNonRestartable() {
        // A bare Object has no bean properties - Jackson refuses to serialize it.
        ExecutionContext context = ExecutionContext
                .newFlow(Map.of("ok", "value", "bad", new Object()), Map.of());

        ContextJsonCodec.Snapshot snapshot = codec.snapshotContext(context);
        assertThat(snapshot.isRestartable()).isFalse();

        // The healthy value is still traced; only the bad one is replaced with a marker.
        ContextJsonCodec.DecodedContext decoded = codec.readContext(snapshot.getJson());
        assertThat(decoded.getInputData()).containsEntry("ok", "value");
        assertThat(decoded.getInputData().get("bad")).asString()
                .contains(ContextJsonCodec.UNSERIALIZABLE_MARKER);
    }

    @Test
    void oversizedValueIsTruncatedAndMarksSnapshotNonRestartable() {
        ContextJsonCodec smallLimit = new ContextJsonCodec(16);
        ExecutionContext context = ExecutionContext
                .newFlow(Map.of("big", "x".repeat(100)), Map.of());

        ContextJsonCodec.Snapshot snapshot = smallLimit.snapshotContext(context);
        assertThat(snapshot.isRestartable()).isFalse();
        assertThat(snapshot.getJson()).contains(ContextJsonCodec.TRUNCATED_MARKER);
    }

    @Test
    void snapshotMapRoundTripsAStepOutput() {
        ContextJsonCodec.Snapshot snapshot = codec.snapshotMap(Map.of("json", "{\"documents\":[]}", "pages", 3));
        assertThat(snapshot.isRestartable()).isTrue();
        assertThat(codec.readMap(snapshot.getJson()))
                .containsEntry("json", "{\"documents\":[]}")
                .containsEntry("pages", 3);
    }
}
