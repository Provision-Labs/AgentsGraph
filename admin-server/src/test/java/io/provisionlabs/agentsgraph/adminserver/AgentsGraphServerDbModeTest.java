package io.provisionlabs.agentsgraph.adminserver;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.adminserver.app.AgentsGraphDemo;
import io.provisionlabs.agentsgraph.adminserver.app.AgentsGraphServer;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcConfigStore;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.test.SqlScriptRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the REAL server ({@link AgentsGraphServer}, {@code db} profile, full Spring context with
 * MockMvc instead of a listening socket) from a DATABASE: an in-memory H2 in PostgreSQL mode,
 * seeded by {@code sql/admin-server-db-test-data.sql}. Covers the whole DB-first path - SQL
 * script -&gt; self-provisioned JDBC stores -&gt; reflective processor loading -&gt; engine -&gt;
 * REST API - and proves the in-memory demo stays OUT of the db mode.
 *
 * <p>The test properties point {@code spring.datasource.*} at H2, overriding the {@code db}
 * document of {@code application.yml} (which targets PostgreSQL in real deployments).
 */
@SpringBootTest(classes = AgentsGraphServer.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:AgentsGraphServerDbModeTest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@ActiveProfiles("db")
@AutoConfigureMockMvc
class AgentsGraphServerDbModeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AgentsGraphEngine engine;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void seedDatabase() {
        SqlScriptRunner.run(dataSource, "/sql/admin-server-db-test-data.sql");
        engine.reload();
    }

    @Test
    void bootsFromTheDatabaseWithTheDemoSwitchedOff() throws Exception {
        // The engine really is DB-backed, and the in-memory demo did not leak into this mode.
        assertThat(engine.getConfigStore()).isInstanceOf(JdbcConfigStore.class);
        assertThat(applicationContext.getBeanNamesForType(AgentsGraphDemo.class)).isEmpty();

        mvc.perform(get("/api/agentsgraph/graphs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("db-graph"))
                .andExpect(jsonPath("$[0].version").value("v1"));

        mvc.perform(get("/api/agentsgraph/graphs/db-graph/processors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("echo"))
                .andExpect(jsonPath("$[0].programmatic").value(false))
                .andExpect(jsonPath("$[0].instanceClass")
                        .value("io.provisionlabs.agentsgraph.adminserver.EchoTestProcessor"));
    }

    @Test
    void dbBackedDebugFlowIsTracedAndResumableThroughTheApi() throws Exception {
        ExecutionContext flow = ExecutionContext.newFlow(Map.of("text", "hello db"), Map.of());
        engine.executeDebug("db-graph", flow);

        mvc.perform(get("/api/agentsgraph/executions/{flowId}", flow.getFlowId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // The step ran through the reflectively-loaded processor; in/out land in the step trace.
        mvc.perform(get("/api/agentsgraph/executions/{flowId}/steps", flow.getFlowId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].processorRef").value("echo"))
                .andExpect(jsonPath("$[0].restartable").value(true))
                .andExpect(jsonPath("$[0].output.answer").value("echo: hello db"));

        mvc.perform(post("/api/agentsgraph/executions/{flowId}/steps/0/resume", flow.getFlowId())
                        .contentType("application/json")
                        .content("{\"overrides\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.flowId").isNotEmpty());
    }
}
