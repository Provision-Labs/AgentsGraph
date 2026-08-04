package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Готовый {@link StepTracer} для живого прогресса: переводит события шагов в строку статуса и
 * общий процент. Библиотечная версия трекера, который до этого каждый потребитель писал у себя
 * (WebVane {@code GraphProgressTracker}); передаётся третьим аргументом в
 * {@code engine.execute/executeAsync}.
 *
 * <p>Процент монотонный по построению: выполненные шаги к верхней оценке (обычно
 * {@link GraphDefinition#totalStepCount()} - сумма шагов всех edges, т.к. маршрут заранее
 * неизвестен), с потолком 95% до фактического завершения flow - финальный статус закрывает
 * бар снаружи.
 *
 * <p>Тексты сообщений настраиваются через {@link MessageFormat} (по умолчанию - английские),
 * доставка - через {@link ProgressSink}: статус-стор чата, лог, websocket.
 */
public final class ProgressStepTracer implements StepTracer {

    /** Куда уходят строки прогресса. */
    @FunctionalInterface
    public interface ProgressSink {
        void progress(String message, int percent);
    }

    /** Тексты сообщений - локализация/формат на стороне потребителя. */
    public interface MessageFormat {
        String stepStarted(EdgeDefinition edge, StepDefinition step, int stepIndex, int stepCount);

        String stepSucceeded(EdgeDefinition edge, StepDefinition step, long durationMs);

        String stepFailed(EdgeDefinition edge, StepDefinition step, Throwable failure);
    }

    /** Дефолтные английские сообщения. */
    public static final MessageFormat DEFAULT_MESSAGES = new MessageFormat() {
        @Override
        public String stepStarted(EdgeDefinition edge, StepDefinition step, int stepIndex, int stepCount) {
            return "Edge '" + edge.getId() + "': step '" + step.getProcessorRef()
                    + "' (" + (stepIndex + 1) + "/" + stepCount + ")";
        }

        @Override
        public String stepSucceeded(EdgeDefinition edge, StepDefinition step, long durationMs) {
            return "Edge '" + edge.getId() + "': step '" + step.getProcessorRef()
                    + "' done in " + durationMs + " ms";
        }

        @Override
        public String stepFailed(EdgeDefinition edge, StepDefinition step, Throwable failure) {
            return "Edge '" + edge.getId() + "': step '" + step.getProcessorRef()
                    + "' failed - " + failure.getMessage();
        }
    };

    /** Потолок процента до фактического завершения flow. */
    private static final int CAP = 95;

    private final int totalStepsEstimate;
    private final ProgressSink sink;
    private final MessageFormat messages;
    private final AtomicInteger completedSteps = new AtomicInteger();

    public ProgressStepTracer(int totalStepsEstimate, ProgressSink sink) {
        this(totalStepsEstimate, sink, DEFAULT_MESSAGES);
    }

    public ProgressStepTracer(int totalStepsEstimate, ProgressSink sink, MessageFormat messages) {
        this.totalStepsEstimate = totalStepsEstimate;
        this.sink = sink;
        this.messages = messages;
    }

    /** Трекер с оценкой шагов из самого графа. */
    public static ProgressStepTracer forGraph(GraphDefinition graph, ProgressSink sink) {
        return new ProgressStepTracer(graph.totalStepCount(), sink);
    }

    public static ProgressStepTracer forGraph(GraphDefinition graph, ProgressSink sink, MessageFormat messages) {
        return new ProgressStepTracer(graph.totalStepCount(), sink, messages);
    }

    @Override
    public void stepStarted(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                              int stepCount, ExecutionContext stepInput) {
        sink.progress(messages.stepStarted(edge, step, stepIndex, stepCount), percent(completedSteps.get()));
    }

    @Override
    public void stepSucceeded(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                                ExecutionContext stepInput, Map<String, Object> rawOutput,
                                long startedAtMillis, long durationMs) {
        int done = completedSteps.incrementAndGet();
        sink.progress(messages.stepSucceeded(edge, step, durationMs), percent(done));
    }

    @Override
    public void stepFailed(String nodeId, EdgeDefinition edge, StepDefinition step, int stepIndex,
                             ExecutionContext stepInput, Throwable failure,
                             long startedAtMillis, long durationMs) {
        sink.progress(messages.stepFailed(edge, step, failure), percent(completedSteps.get()));
    }

    private int percent(int completed) {
        if (totalStepsEstimate <= 0) {
            return 0;
        }
        return Math.min(CAP, completed * 100 / totalStepsEstimate);
    }
}
