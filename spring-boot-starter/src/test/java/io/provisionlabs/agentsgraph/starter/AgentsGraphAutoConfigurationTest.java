package io.provisionlabs.agentsgraph.starter;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.config.ConfigStore;
import io.provisionlabs.agentsgraph.config.ProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcConfigStore;
import io.provisionlabs.agentsgraph.trace.TraceStore;
import io.provisionlabs.agentsgraph.web.AgentsGraphAdminController;
import io.provisionlabs.agentsgraph.web.AgentsGraphAdminService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The starter's wiring contract, verified without booting a web server. */
class AgentsGraphAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentsGraphAutoConfiguration.class));

    private static DataSource freshH2() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:StarterTest_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    @Test
    void dataSourceAloneYieldsJdbcStoresEngineAndAdminService() {
        runner.withBean(DataSource.class, AgentsGraphAutoConfigurationTest::freshH2)
                .run(context -> {
                    assertThat(context).hasSingleBean(ConfigStore.class)
                            .hasSingleBean(ProcessorDefinitionStore.class)
                            .hasSingleBean(TraceStore.class)
                            .hasSingleBean(AgentsGraphEngine.class)
                            .hasSingleBean(AgentsGraphAdminService.class);
                    assertThat(context.getBean(ConfigStore.class)).isInstanceOf(JdbcConfigStore.class);
                    // Non-web context: the REST controller backs off.
                    assertThat(context).doesNotHaveBean(AgentsGraphAdminController.class);
                });
    }

    @Test
    void userDefinedEngineWinsAndStillGetsTheAdminService() {
        AgentsGraphEngine userEngine = AgentsGraphEngine.inMemory();
        runner.withBean("myEngine", AgentsGraphEngine.class, () -> userEngine)
                .run(context -> {
                    assertThat(context.getBean(AgentsGraphEngine.class)).isSameAs(userEngine);
                    // No DataSource -> no JDBC stores were forced on the app.
                    assertThat(context).doesNotHaveBean(ConfigStore.class);
                    assertThat(context).hasSingleBean(AgentsGraphAdminService.class);
                });
    }

    @Test
    void webApplicationGetsTheRestController() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentsGraphAutoConfiguration.class))
                .withBean(AgentsGraphEngine.class, AgentsGraphEngine::inMemory)
                .run(context -> assertThat(context).hasSingleBean(AgentsGraphAdminController.class));
    }

    @Test
    void nothingIsCreatedWithoutADataSourceOrEngine() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(AgentsGraphEngine.class);
            assertThat(context).doesNotHaveBean(AgentsGraphAdminService.class);
        });
    }
}
