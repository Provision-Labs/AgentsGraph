package io.provisionlabs.agentsgraph.spring;

import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import io.provisionlabs.agentsgraph.engine.ProcessorInstantiator;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * {@link ProcessorInstantiator} backed by a Spring {@link ApplicationContext}: processors that
 * need live dependencies (stores, HTTP/LLM clients, services) load from {@code agentsgraph_processor}
 * rows like every other processor - no per-processor beans and no programmatic-processor map.
 *
 * <p>Rules for {@code instance_class}:
 * <ul>
 *   <li>{@code bean:name} - an existing bean of the context is used as the processor;</li>
 *   <li>otherwise the class is created through its public no-arg constructor and its fields
 *       annotated {@link Autowired} receive beans by type (or by name via {@link Qualifier} when
 *       several beans of the type exist), fields annotated {@link Value} receive property values
 *       ({@code ${some.property:default}}), resolved through the bean factory's embedded-value
 *       resolvers first and the {@code Environment} second.</li>
 * </ul>
 * The annotations are processed here rather than left to {@code <context:annotation-config/>},
 * so the instantiator works in XML-only contexts too; where annotation processing is enabled,
 * Spring's own {@code autowireBean} runs first and this class only fills what is still empty.
 * A missing required dependency throws, which {@code ProcessorLoader} isolates to that one row.
 *
 * <p>Wiring:
 * <pre>{@code
 * <bean id="processorInstantiator" class="io.provisionlabs.agentsgraph.spring.SpringProcessorInstantiator"/>
 * <bean id="agentsGraphEngine" class="io.provisionlabs.agentsgraph.AgentsGraphEngine">
 *     ...stores...
 *     <property name="processorInstantiator" ref="processorInstantiator"/>
 * </bean>
 * }</pre>
 */
public final class SpringProcessorInstantiator implements ProcessorInstantiator, ApplicationContextAware {

    /** {@code instance_class} prefix that names an existing bean instead of a class. */
    public static final String BEAN_PREFIX = "bean:";

    private ApplicationContext context;

    public SpringProcessorInstantiator() {
    }

    public SpringProcessorInstantiator(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.context = applicationContext;
    }

    @Override
    public Object instantiate(ProcessorDefinition definition) throws Exception {
        String instanceClass = definition.getInstanceClass() == null ? "" : definition.getInstanceClass().trim();
        if (instanceClass.startsWith(BEAN_PREFIX)) {
            requireContext();
            return context.getBean(instanceClass.substring(BEAN_PREFIX.length()).trim());
        }
        Object instance = ProcessorInstantiator.REFLECTIVE.instantiate(definition);
        if (context != null) {
            context.getAutowireCapableBeanFactory().autowireBean(instance);
            inject(instance);
        }
        return instance;
    }

    /** Fills the still-empty {@code @Autowired} / {@code @Value} fields of {@code instance} from the context. */
    void inject(Object instance) throws IllegalAccessException {
        for (Class<?> type = instance.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Autowired autowired = field.getAnnotation(Autowired.class);
                Value value = field.getAnnotation(Value.class);
                if (autowired == null && value == null) {
                    continue;
                }
                field.setAccessible(true);
                if (field.get(instance) != null && !field.getType().isPrimitive()) {
                    continue;                                   // already injected (annotation-config on)
                }
                if (value != null) {
                    field.set(instance, convert(resolve(value.value()), field.getType(), field));
                } else {
                    Object bean = bean(field, autowired.required());
                    if (bean != null) {
                        field.set(instance, bean);
                    }
                }
            }
        }
    }

    private Object bean(Field field, boolean required) {
        Qualifier qualifier = field.getAnnotation(Qualifier.class);
        try {
            if (qualifier != null && !qualifier.value().isBlank()) {
                return context.getBean(qualifier.value(), field.getType());
            }
            Map<String, ?> candidates = context.getBeansOfType(field.getType());
            if (candidates.size() == 1) {
                return candidates.values().iterator().next();
            }
            if (candidates.isEmpty()) {
                throw new NoSuchBeanDefinitionException(field.getType());
            }
            throw new IllegalStateException("Several beans of type " + field.getType().getName() + " ("
                    + String.join(", ", candidates.keySet()) + ") for field " + describe(field)
                    + " - add @Qualifier(\"beanName\")");
        } catch (NoSuchBeanDefinitionException e) {
            if (!required) {
                return null;
            }
            throw new IllegalStateException("No bean of type " + field.getType().getName()
                    + " for field " + describe(field), e);
        }
    }

    /** Placeholders: the bean factory's embedded-value resolvers (property configurers) first, then the Environment. */
    private String resolve(String expression) {
        String resolved = expression;
        if (context.getAutowireCapableBeanFactory() instanceof ConfigurableBeanFactory) {
            String viaFactory = ((ConfigurableBeanFactory) context.getAutowireCapableBeanFactory())
                    .resolveEmbeddedValue(expression);
            if (viaFactory != null) {
                resolved = viaFactory;
            }
        }
        if (resolved.contains("${")) {
            resolved = context.getEnvironment().resolvePlaceholders(resolved);
        }
        return resolved;
    }

    private static Object convert(String value, Class<?> type, Field field) {
        if (value == null || value.contains("${")) {
            throw new IllegalStateException("Property for field " + describe(field) + " did not resolve: " + value);
        }
        String v = value.trim();
        if (type == String.class) {
            return v;
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(v);
        }
        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(v);
        }
        if (type == long.class || type == Long.class) {
            return Long.parseLong(v);
        }
        if (type == double.class || type == Double.class) {
            return Double.parseDouble(v);
        }
        throw new IllegalStateException("@Value supports String, boolean, int, long, double; field "
                + describe(field) + " is " + type.getName());
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("SpringProcessorInstantiator has no ApplicationContext");
        }
    }

    private static String describe(Field field) {
        return field.getDeclaringClass().getSimpleName() + "." + field.getName();
    }
}
