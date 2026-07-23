package io.provisionlabs.agentsgraph.test;

import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.engine.Processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A scripted {@link Processor} stand-in for a step that would otherwise call an external service
 * (LLM, OCR, classifier, ...) - the graph's routing/threading/fallback logic runs for real, only
 * this one step's behaviour is canned, with no network access and no AI-API token spend.
 *
 * <p>Every invocation's {@link ExecutionContext} is recorded, so a test can assert not just on
 * the flow's final state but on what this step actually received:
 *
 * <pre>{@code
 * MockProcessor llm = MockProcessor.returning(Map.of("llmContent", "ANSWER"));
 * harness.mockProcessor("llm-completion", llm);
 * ...
 * assertThat(llm.invocationCount()).isEqualTo(1);
 * assertThat(llm.lastInvocation().getAccumulatedState()).containsKey("prompt");
 * }</pre>
 */
public final class MockProcessor implements Processor {

    private final BiFunction<ExecutionContext, StepDefinition, Map<String, Object>> behaviour;
    private final List<ExecutionContext> invocations = Collections.synchronizedList(new ArrayList<>());

    private MockProcessor(BiFunction<ExecutionContext, StepDefinition, Map<String, Object>> behaviour) {
        this.behaviour = behaviour;
    }

    /** Always returns the same output map. */
    public static MockProcessor returning(Map<String, Object> output) {
        return new MockProcessor((context, step) -> output);
    }

    /**
     * Returns the given outputs one per invocation, in order, repeating the last one once the
     * sequence is exhausted - the shape of a replay fixture: a processor that was invoked N times
     * in a recorded run answers with its N recorded outputs (see
     * {@code AgentsGraphTestHarness.mocksFromDump}).
     */
    public static MockProcessor returningSequence(List<Map<String, Object>> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("returningSequence needs at least one output");
        }
        List<Map<String, Object>> sequence = List.copyOf(outputs);
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        return new MockProcessor((context, step) ->
                sequence.get(Math.min(next.getAndIncrement(), sequence.size() - 1)));
    }

    /** Always throws, simulating an unavailable external service - for testing fallback paths. */
    public static MockProcessor failing(String message) {
        return new MockProcessor((context, step) -> {
            throw new IllegalStateException(message);
        });
    }

    /** Computes the output from the incoming context/step - for dynamic scripting. */
    public static MockProcessor answering(BiFunction<ExecutionContext, StepDefinition, Map<String, Object>> behaviour) {
        return new MockProcessor(behaviour);
    }

    @Override
    public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
        invocations.add(context);
        return behaviour.apply(context, step);
    }

    /** Every context this processor was invoked with, in order. */
    public List<ExecutionContext> getInvocations() {
        return List.copyOf(invocations);
    }

    public int invocationCount() {
        return invocations.size();
    }

    /** The most recent invocation's context, or {@code null} if never invoked. */
    public ExecutionContext lastInvocation() {
        synchronized (invocations) {
            return invocations.isEmpty() ? null : invocations.get(invocations.size() - 1);
        }
    }
}
