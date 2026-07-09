package io.provisionlabs.agentsgraph.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves a {@code routing_delegate.ref} to the {@link RoutingDelegate} implementation that serves it. */
public final class RoutingDelegateRegistry {

    private final Map<String, RoutingDelegate> delegates = new ConcurrentHashMap<>();

    public RoutingDelegateRegistry register(String ref, RoutingDelegate delegate) {
        delegates.put(ref, delegate);
        return this;
    }

    public RoutingDelegate resolve(String ref) {
        RoutingDelegate delegate = delegates.get(ref);
        if (delegate == null) {
            throw new AgentsGraphException("No routing delegate registered for ref '" + ref + "'");
        }
        return delegate;
    }
}
