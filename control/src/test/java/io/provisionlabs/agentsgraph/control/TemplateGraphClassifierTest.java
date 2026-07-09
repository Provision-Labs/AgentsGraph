package io.provisionlabs.agentsgraph.control;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.InMemoryConfigStore;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateGraphClassifierTest {

    private InMemoryConfigStore configStore;
    private TemplateGraphClassifier classifier;

    @BeforeEach
    void setUp() {
        configStore = new InMemoryConfigStore();
        configStore.putGraph(graph("accounting", "file:accountant"));
        configStore.putGraph(graph("passport", "паспорт"));
        classifier = new TemplateGraphClassifier(configStore, "ocr-default", "llm-default");
    }

    private static GraphDefinition graph(String id, String template) {
        NodeDefinition node = NodeDefinition.builder("entry")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", "edge_main")
                .fallbackEdgeId("edge_main")
                .build();
        return GraphDefinition.builder(id, "v1")
                .entryNodeId("entry")
                .node(node)
                .edge(EdgeDefinition.builder("edge_main").build())
                .template(template)
                .build();
    }

    @Test
    void explicitGraphIdOverrideBypassesMatching() {
        String result = classifier.classify(Map.of(TemplateGraphClassifier.INPUT_GRAPH_ID, "whatever-id"));
        assertThat(result).isEqualTo("whatever-id");
    }

    @Test
    void matchesAFileTemplateWhenInputHasAFileAndTheMatchingTag() {
        String result = classifier.classify(Map.of(
                TemplateGraphClassifier.INPUT_HAS_FILE, true,
                TemplateGraphClassifier.INPUT_TEMPLATE_TAG, "accountant"));
        assertThat(result).isEqualTo("accounting");
    }

    @Test
    void aFileTemplateDoesNotMatchWithoutAFileEvenIfTheTagMatches() {
        String result = classifier.classify(Map.of(
                TemplateGraphClassifier.INPUT_HAS_FILE, false,
                TemplateGraphClassifier.INPUT_TEMPLATE_TAG, "accountant"));
        assertThat(result).isEqualTo("llm-default");
    }

    @Test
    void matchesATextTemplateAgainstTheTemplateTag() {
        String result = classifier.classify(Map.of(TemplateGraphClassifier.INPUT_TEMPLATE_TAG, "паспорт"));
        assertThat(result).isEqualTo("passport");
    }

    @Test
    void fallsBackToTheFileDefaultWhenNothingMatchesButThereIsAFile() {
        InMemoryConfigStore emptyStore = new InMemoryConfigStore();
        TemplateGraphClassifier c = new TemplateGraphClassifier(emptyStore, "ocr-default", "llm-default");

        assertThat(c.classify(Map.of(TemplateGraphClassifier.INPUT_HAS_FILE, true))).isEqualTo("ocr-default");
        assertThat(c.classify(Map.of())).isEqualTo("llm-default");
    }

    @Test
    void throwsWhenNothingMatchesAndNoDefaultIsConfigured() {
        TemplateGraphClassifier noDefaults = new TemplateGraphClassifier(new InMemoryConfigStore(), null, null);
        assertThatThrownBy(() -> noDefaults.classify(Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
