package io.provisionlabs.agentsgraph.web.dto;

import java.util.Map;

/** Body of the resume endpoint: optional overrides merged over the restored accumulated state. */
public final class ResumeRequest {

    private Map<String, Object> overrides;

    public Map<String, Object> getOverrides() {
        return overrides;
    }

    public void setOverrides(Map<String, Object> overrides) {
        this.overrides = overrides;
    }
}
