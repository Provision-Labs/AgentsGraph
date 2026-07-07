package com.provisionlabs.agentsgraph.engine;

import java.util.Map;

/** Default {@link OutputSink} that discards everything; used when a caller doesn't need saved outputs. */
public final class NoopOutputSink implements OutputSink {

    public static final NoopOutputSink INSTANCE = new NoopOutputSink();

    @Override
    public void save(String flowId, Map<String, Object> outputs) {
        // intentionally does nothing
    }
}
