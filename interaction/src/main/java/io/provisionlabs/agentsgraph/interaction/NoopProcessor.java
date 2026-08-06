package io.provisionlabs.agentsgraph.interaction;

import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;

import java.util.Map;

/**
 * Ничего не делает. Для edges, чья роль - только роутинг/теги: например терминальный edge
 * review-ветки, который добавляет тег {@code review_pending} (edge обязан иметь хотя бы один шаг).
 */
public final class NoopProcessor implements Processor {

    @Override
    public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
        return Map.of();
    }
}
