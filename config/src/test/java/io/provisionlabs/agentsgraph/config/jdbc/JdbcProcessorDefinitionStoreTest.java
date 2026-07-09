package io.provisionlabs.agentsgraph.config.jdbc;

import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcProcessorDefinitionStoreTest {

    private JdbcProcessorDefinitionStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:JdbcProcessorDefinitionStoreTest_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcProcessorDefinitionStore.createSchema(dataSource);
        store = new JdbcProcessorDefinitionStore(dataSource);
    }

    @Test
    void savesAndReloadsAProcessorDefinition() {
        store.put(new ProcessorDefinition(
                "docscan_ocr", "docscan-ocr", true, "org.webvane.pipes.DocScanOcrProcessor",
                Map.of("processUrl", "http://10.64.0.40:8109")));

        Optional<ProcessorDefinition> reloaded = store.find("docscan_ocr");

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().isExternal()).isTrue();
        assertThat(reloaded.get().getInstanceClass()).isEqualTo("org.webvane.pipes.DocScanOcrProcessor");
        assertThat(reloaded.get().getParams()).containsEntry("processUrl", "http://10.64.0.40:8109");
    }

    @Test
    void updatingAnExistingDefinitionOverwritesItsRow() {
        store.put(new ProcessorDefinition("p1", "P1", false, "com.example.P1", Map.of()));
        store.put(new ProcessorDefinition("p1", "P1 renamed", true, "com.example.P1", Map.of("x", 1)));

        ProcessorDefinition reloaded = store.find("p1").orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("P1 renamed");
        assertThat(reloaded.isExternal()).isTrue();
        assertThat(reloaded.getParams()).containsEntry("x", 1);
    }

    @Test
    void findAllReturnsEveryProcessorDefinition() {
        store.put(new ProcessorDefinition("p1", "P1", false, "com.example.P1", Map.of()));
        store.put(new ProcessorDefinition("p2", "P2", true, "com.example.P2", Map.of()));

        List<ProcessorDefinition> all = store.findAll();

        assertThat(all).extracting(ProcessorDefinition::getId).containsExactlyInAnyOrder("p1", "p2");
    }
}
