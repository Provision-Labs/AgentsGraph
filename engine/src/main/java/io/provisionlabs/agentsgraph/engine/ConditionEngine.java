package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates the declarative {@code routing_table} of a {@link RoutingStrategy#RULES} node.
 *
 * <p>Each routing rule is a simple {@code "path==value"} equality condition evaluated against a
 * merged view of {@code accumulated_state} (highest priority) and {@code input_data}, e.g.
 * {@code "intent==billing"}. The literal key {@code "default"} matches unconditionally and is
 * evaluated last, acting as a catch-all rule.
 */
public final class ConditionEngine {

    public Optional<String> evaluate(Map<String, String> routingTable, ExecutionContext context) {
        Map<String, Object> scope = mergedScope(context);

        String defaultEdge = null;
        for (Map.Entry<String, String> rule : routingTable.entrySet()) {
            String condition = rule.getKey();
            if ("default".equals(condition)) {
                defaultEdge = rule.getValue();
                continue;
            }
            if (matches(condition, scope)) {
                return Optional.of(rule.getValue());
            }
        }
        return Optional.ofNullable(defaultEdge);
    }

    private boolean matches(String condition, Map<String, Object> scope) {
        int separator = condition.indexOf("==");
        if (separator < 0) {
            throw new AgentsGraphException("Unsupported routing condition '" + condition
                    + "', expected 'path==value' or 'default'");
        }
        String path = condition.substring(0, separator).trim();
        String expected = condition.substring(separator + 2).trim();
        Object actual = resolvePath(path, scope);
        return expected.equals(String.valueOf(actual));
    }

    @SuppressWarnings("unchecked")
    private Object resolvePath(String path, Map<String, Object> scope) {
        Object current = scope;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(segment);
        }
        return current;
    }

    private Map<String, Object> mergedScope(ExecutionContext context) {
        Map<String, Object> scope = new LinkedHashMap<>(context.getInputData());
        scope.putAll(context.getAccumulatedState());
        return scope;
    }
}
