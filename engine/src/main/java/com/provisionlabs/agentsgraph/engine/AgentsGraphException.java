package com.provisionlabs.agentsgraph.engine;

/** Raised when the runtime cannot produce a routing decision or a step pipeline fails. */
public class AgentsGraphException extends RuntimeException {

    public AgentsGraphException(String message) {
        super(message);
    }

    public AgentsGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
