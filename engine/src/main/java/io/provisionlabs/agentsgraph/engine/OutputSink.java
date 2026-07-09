package io.provisionlabs.agentsgraph.engine;

import java.util.Map;

/**
 * Receives each edge's {@code outputToSave}-selected fields (see {@link EdgeResult#getSavedOutputs()}),
 * mirroring the legacy docscan-pipeline's {@code BodyItemSaver}: a place to persist extracted
 * documents/tables/results independently of what flows through the graph itself.
 */
public interface OutputSink {

    void save(String flowId, Map<String, Object> outputs);
}
