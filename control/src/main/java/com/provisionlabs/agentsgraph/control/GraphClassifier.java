package com.provisionlabs.agentsgraph.control;

import java.util.Map;

/**
 * Picks which deployed graph should handle a given input, e.g. so an upstream integration (a
 * chat bot, an HTTP endpoint, a queue consumer, ...) doesn't need to hardcode a graph id.
 * Deliberately transport-agnostic: implementations receive a plain {@code Map} rather than any
 * particular request type, so this interface can be reused by integrations this framework doesn't
 * know about (a future chat-bot front end, for instance) without pulling their types in here.
 */
public interface GraphClassifier {

    /** Returns the id of the graph that should handle {@code input}. */
    String classify(Map<String, Object> input);
}
