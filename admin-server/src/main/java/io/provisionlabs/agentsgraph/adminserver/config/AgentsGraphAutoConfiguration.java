package io.provisionlabs.agentsgraph.adminserver.config;

import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.adminserver.AgentsGraphAdminController;
import io.provisionlabs.agentsgraph.adminserver.service.AgentsGraphAdminService;
import io.provisionlabs.agentsgraph.config.ConfigStore;
import io.provisionlabs.agentsgraph.config.ProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcConfigStore;
import io.provisionlabs.agentsgraph.config.jdbc.JdbcProcessorDefinitionStore;
import io.provisionlabs.agentsgraph.trace.TraceStore;
import io.provisionlabs.agentsgraph.trace.jdbc.JdbcTraceStore;


import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;

/**
 * Spring Boot auto-configuration of the admin API: point the application at a {@code DataSource}
 * and get the three JDBC stores (each provisioning its own schema), the {@link AgentsGraphEngine}
 * and the {@code /api/agentsgraph/**} admin API - the backend of the AgentsGraph UI - with zero
 * further wiring. Every bean is {@code @ConditionalOnMissingBean}, so an application that wires
 * its own stores/engine (e.g. with programmatic processors, as WebVane's docscan module does)
 * keeps them: only the missing pieces are filled in.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(AgentsGraphWebProperties.class)
public class AgentsGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConfigStore.class)
    @ConditionalOnBean(DataSource.class)
    public ConfigStore agentsGraphConfigStore(DataSource dataSource) {
        return new JdbcConfigStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(ProcessorDefinitionStore.class)
    @ConditionalOnBean(DataSource.class)
    public ProcessorDefinitionStore agentsGraphProcessorDefinitionStore(DataSource dataSource) {
        return new JdbcProcessorDefinitionStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(TraceStore.class)
    @ConditionalOnBean(DataSource.class)
    public TraceStore agentsGraphTraceStore(DataSource dataSource) {
        return new JdbcTraceStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ConfigStore.class, ProcessorDefinitionStore.class, TraceStore.class})
    public AgentsGraphEngine agentsGraphEngine(ConfigStore configStore,
                                                ProcessorDefinitionStore processorDefinitionStore,
                                                TraceStore traceStore) {
        return new AgentsGraphEngine(configStore, processorDefinitionStore, traceStore);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentsGraphEngine.class)
    public AgentsGraphAdminService agentsGraphAdminService(AgentsGraphEngine engine) {
        return new AgentsGraphAdminService(engine);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentsGraphAdminService.class)
    @ConditionalOnWebApplication
    public AgentsGraphAdminController agentsGraphAdminController(AgentsGraphAdminService service) {
        return new AgentsGraphAdminController(service);
    }

    @Bean
    @ConditionalOnWebApplication
    public WebMvcConfigurer agentsGraphCorsConfigurer(AgentsGraphWebProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                String origins = properties.getCorsOrigins();
                if (origins != null && !origins.isBlank()) {
                    registry.addMapping("/api/agentsgraph/**")
                            .allowedOrigins(origins.split("\\s*,\\s*"))
                            .allowedMethods("GET", "POST");
                }
            }
        };
    }
}
