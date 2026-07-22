package io.provisionlabs.agentsgraph.adminserver;

import io.provisionlabs.agentsgraph.adminserver.config.AgentsGraphAutoConfiguration;
import io.provisionlabs.agentsgraph.adminserver.config.AgentsGraphDemo;
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
 * auto-configuration with your {@code spring.datasource.*} (see
 * {@code application-db.properties}); the three JDBC stores provision their own schemas.
 */
@SpringBootApplication
public class AgentsGraphServer {

    public static void main(String[] args) {
        SpringApplication.run(AgentsGraphServer.class, args);
    }
}
