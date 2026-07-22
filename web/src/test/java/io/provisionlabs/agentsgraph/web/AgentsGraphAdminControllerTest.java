package io.provisionlabs.agentsgraph.web;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level test of the admin REST API via MockMvc (standalone setup - the real controller, the
 * real service, a live in-memory engine; no server socket involved, so it runs anywhere).
 */
class AgentsGraphAdminControllerTest {

    private AgentsGraphEngine engine;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        engine = AgentsGraphEngine.inMemory();
        engine.registerProcessor("ocr", (context, step) -> Map.of("json", "{\"documents\":[]}"));
        engine.registerProcessor("llm", (context, step) ->
                Map.of("summary", "SUMMARY(" + context.getAccumulatedState().get("json") + ")"));

        NodeDefinition entry = NodeDefinition.builder("entry")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_pipeline")
                .build();
        EdgeDefinition pipeline = EdgeDefinition.builder("edge_pipeline")
                .step(new StepDefinition("s_ocr", "ocr", Map.of()))
                .step(new StepDefinition("s_llm", "llm", Map.of()))
                .build();
        engine.deployGraph(GraphDefinition.builder("doc-flow", "v1")
                .entryNodeId("entry").node(entry).edge(pipeline).build());

        mvc = MockMvcBuilders.standaloneSetup(
                new AgentsGraphAdminController(new AgentsGraphAdminService(engine))).build();
    }

    @Test
    void graphEndpointsServeListJsonAndProcessors() throws Exception {
        mvc.perform(get("/api/agentsgraph/graphs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("doc-flow"))
                .andExpect(jsonPath("$[0].nodeCount").value(1))
                .andExpect(jsonPath("$[0].edgeCount").value(1));

        mvc.perform(get("/api/agentsgraph/graphs/doc-flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("doc-flow"))
                .andExpect(jsonPath("$.entry_node_id").value("entry"));

        mvc.perform(get("/api/agentsgraph/graphs/doc-flow/processors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ocr"))
                .andExpect(jsonPath("$[0].programmatic").value(true));
    }

    @Test
    void executionAndStepEndpointsExposeADebugRun() throws Exception {
        ExecutionContext debug = ExecutionContext.newFlow(Map.of("file", "doc.pdf"), Map.of());
        engine.executeDebug("doc-flow", debug);

        mvc.perform(get("/api/agentsgraph/executions").param("status", "completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flowId").value(debug.getFlowId()))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        mvc.perform(get("/api/agentsgraph/executions/{flowId}/steps", debug.getFlowId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].processorRef").value("llm"))
                // Parsed JSON structures, not escaped strings.
                .andExpect(jsonPath("$[1].inputContext.accumulated_state.json").exists())
                .andExpect(jsonPath("$[1].output.summary").exists());
    }

    @Test
    void resumeEndpointReRunsFromTheStepWithOverrides() throws Exception {
        ExecutionContext debug = ExecutionContext.newFlow(Map.of("file", "doc.pdf"), Map.of());
        engine.executeDebug("doc-flow", debug);

        mvc.perform(post("/api/agentsgraph/executions/{flowId}/steps/1/resume", debug.getFlowId())
                        .contentType("application/json")
                        .content("{\"overrides\":{\"json\":\"{\\\"documents\\\":[\\\"corrected\\\"]}\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.flowId").isNotEmpty())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void unknownIdsTranslateTo404WithAnErrorBody() throws Exception {
        mvc.perform(get("/api/agentsgraph/executions/no-such-flow"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
