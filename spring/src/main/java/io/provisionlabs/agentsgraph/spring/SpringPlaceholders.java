package io.provisionlabs.agentsgraph.spring;

import io.provisionlabs.agentsgraph.config.Placeholders;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * {@link Placeholders} for a Spring application: {@code ${{NAME}}} in processor and step params takes the
 * environment variable {@code NAME} of the container first, then the application's setting {@code NAME}
 * ({@code application.yml}, {@code -DNAME=...}, any property source of the {@link Environment}), then the default
 * written in the placeholder ({@code ${{NAME:default}}}). The origin shown to admins is {@code environment} or
 * {@code settings} accordingly.
 *
 * <p>Wiring (XML), the {@code environment} bean being the context's own {@link Environment}:
 * <pre>{@code
 * <bean id="graphPlaceholders" class="io.provisionlabs.agentsgraph.spring.SpringPlaceholders" factory-method="create">
 *     <constructor-arg ref="environment"/>
 * </bean>
 * <bean id="agentsGraphEngine" class="io.provisionlabs.agentsgraph.AgentsGraphEngine">
 *     ...
 *     <property name="placeholders" ref="graphPlaceholders"/>
 * </bean>
 * }</pre>
 */
public final class SpringPlaceholders {

    /** Origin of a value taken from the application's settings rather than from an environment variable. */
    public static final String SETTINGS = "settings";

    private SpringPlaceholders() {
    }

    public static Placeholders create(Environment environment) {
        if (environment == null) {
            return Placeholders.environment();
        }
        return new Placeholders(List.of(
                Placeholders.Source.environment(),
                new Placeholders.Source(SETTINGS, environment::getProperty)));
    }
}
