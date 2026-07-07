package com.provisionlabs.agentsgraph.config.jdbc;

import com.provisionlabs.agentsgraph.config.EdgeDefinition;
import com.provisionlabs.agentsgraph.config.GraphDefinition;
import com.provisionlabs.agentsgraph.config.NodeDefinition;
import com.provisionlabs.agentsgraph.config.RoutingStrategy;
import com.provisionlabs.agentsgraph.config.StepDefinition;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConfigStoreTest {

    private JdbcDataSource dataSource;
    private JdbcConfigStore store;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:JdbcConfigStoreTest_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcConfigStore.createSchema(dataSource);
        store = new JdbcConfigStore(dataSource);
    }

    private GraphDefinition sampleGraph(String id) {
        NodeDefinition node = NodeDefinition.builder("entry")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_main")
                .fallbackEdgeId("edge_main")
                .build();
        EdgeDefinition edge = EdgeDefinition.builder("edge_main")
                .step(new StepDefinition("s0", "ocr", Map.of("k", "v")))
                .build();
        return GraphDefinition.builder(id, "v1")
                .entryNodeId("entry")
                .node(node)
                .edge(edge)
                .template("file:default")
                .build();
    }

    @Test
    void savesAndReloadsAGraphById() {
        store.putGraph(sampleGraph("ocr-accounting"));

        Optional<GraphDefinition> reloaded = store.findGraph("ocr-accounting");

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getId()).isEqualTo("ocr-accounting");
        assertThat(reloaded.get().getTemplates()).containsExactly("file:default");
        assertThat(reloaded.get().getEdge("edge_main").getSteps()).hasSize(1);
    }

    @Test
    void updatingAnExistingGraphOverwritesItsRow() {
        store.putGraph(sampleGraph("g1"));
        store.putGraph(sampleGraph("g1"));

        assertThat(store.findAll()).hasSize(1);
    }

    @Test
    void findAllReturnsEveryDeployedGraph() {
        store.putGraph(sampleGraph("g1"));
        store.putGraph(sampleGraph("g2"));

        List<GraphDefinition> all = store.findAll();

        assertThat(all).extracting(GraphDefinition::getId).containsExactlyInAnyOrder("g1", "g2");
    }

    @Test
    void findGraphReturnsEmptyForUnknownId() {
        assertThat(store.findGraph("missing")).isEmpty();
    }
}
