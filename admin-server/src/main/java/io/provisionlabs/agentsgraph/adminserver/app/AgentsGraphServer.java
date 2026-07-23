package io.provisionlabs.agentsgraph.adminserver.app;

import io.provisionlabs.agentsgraph.adminserver.config.AgentsGraphAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The runnable AgentsGraph admin-API server - the backend of the
 * <a href="https://github.com/Provision-Labs/agentsgraph-ui">AgentsGraph UI</a>, started straight
 * from this build:
 *
 * <pre>{@code ./gradlew :admin-server:bootRun }</pre>
 *
 * <p><b>Default: pure in-memory demo mode</b> - the {@code inmemory} profile is the default (see
 * {@code application.properties}), activating {@link AgentsGraphDemo}: an in-memory engine with a
 * demo graph and two seeded DEBUG flows (one successful, one intentionally failing at the LLM
 * step), so the UI has graphs, executions and a resumable failed step to show immediately.
 * {@link AgentsGraphAutoConfiguration} contributes the {@code /api/agentsgraph/**} REST API
 * around whichever engine is present.
 *
 * <p><b>Database-backed mode</b> - the PostgreSQL driver ships with the server:
 *
 * <pre>{@code ./gradlew :admin-server:bootRun --args='--spring.profiles.active=db' }</pre>
 *
 * The {@code db} profile disables the demo entirely and re-enables Boot's DataSource
 * auto-configuration with your {@code spring.datasource.*} (see the {@code db} document in
 * {@code application.yml}); the three JDBC stores provision their own schemas.
 *
 * <p><b>Why the app lives in the {@code .app} subpackage:</b> component scanning starts here, and
 * it must NOT reach the library classes ({@code adminserver}/{@code .config}/{@code .service}) -
 * a scanned {@code @RestController}/{@code @AutoConfiguration} is processed in the user-config
 * phase, where {@code @ConditionalOnBean(DataSource.class)} always fails (the DataSource bean is
 * contributed later by Boot's auto-configuration). The admin API therefore loads ONLY through
 * {@code META-INF/spring/...AutoConfiguration.imports}, in the correct phase.
 */
@SpringBootApplication
public class AgentsGraphServer {

    public static void main(String[] args) {
        SpringApplication.run(AgentsGraphServer.class, args);
    }
}
