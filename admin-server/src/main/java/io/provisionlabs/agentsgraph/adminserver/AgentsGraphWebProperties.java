package io.provisionlabs.agentsgraph.adminserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code agentsgraph.web.*} settings of the auto-configured admin API. */
@ConfigurationProperties(prefix = "agentsgraph.web")
public class AgentsGraphWebProperties {

    /**
     * Comma-separated origins allowed to call {@code /api/agentsgraph/**} cross-origin - set to
     * the AgentsGraph UI's dev-server origin (e.g. {@code http://localhost:4200}) during local
     * development; leave empty (the default) when the UI is served from the same origin or
     * behind the same reverse proxy.
     */
    private String corsOrigins = "";

    public String getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }
}
