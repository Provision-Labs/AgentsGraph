package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.ProcessorDefinition;

import java.util.function.UnaryOperator;

/**
 * Strategy for turning a {@link ProcessorDefinition} (an {@code agentsgraph_processor} row) into a
 * processor object. {@link ProcessorLoader} then checks the type, calls {@link Processor#init} with
 * the row's params and registers the instance.
 *
 * <p>The default, {@link #REFLECTIVE}, loads {@code instance_class} through its public no-arg
 * constructor - fine for processors that only need their params. Processors that need live
 * dependencies (HTTP clients, stores, an LLM client) used to be excluded from DB-driven loading and
 * handed to the engine as pinned programmatic processors, one bean per processor. An application
 * can instead plug in an instantiator that knows its DI container: with Spring, for example,
 * <pre>{@code
 * ProcessorInstantiator.REFLECTIVE.andThen(instance -> {
 *     beanFactory.autowireBean(instance);      // @Autowired / @Value fields get injected
 *     return instance;
 * })
 * }</pre>
 * so every processor - including the ones with dependencies - is an ordinary DB row, and the only
 * bean left in the wiring is the engine itself. Custom instantiators may also resolve special
 * {@code instance_class} spellings (e.g. {@code bean:ragAnswerProcessor}) to existing container
 * objects.
 */
@FunctionalInterface
public interface ProcessorInstantiator {

    /**
     * Creates the raw instance for {@code definition}. May return anything; a non-{@link Processor}
     * result is reported by the loader as a per-definition failure, and any exception is isolated
     * the same way (the rest of the batch still loads).
     */
    Object instantiate(ProcessorDefinition definition) throws Exception;

    /** {@code instance_class} via its public no-arg constructor. */
    ProcessorInstantiator REFLECTIVE = definition ->
            Class.forName(definition.getInstanceClass()).getDeclaredConstructor().newInstance();

    /**
     * Post-processing hook: the instance produced by this instantiator is passed through
     * {@code postProcess} before the loader sees it - the place for dependency injection.
     */
    default ProcessorInstantiator andThen(UnaryOperator<Object> postProcess) {
        return definition -> postProcess.apply(instantiate(definition));
    }
}
