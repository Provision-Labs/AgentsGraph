package com.provisionlabs.agentsgraph.control;

import com.provisionlabs.agentsgraph.trace.ExecutionStatus;

import java.util.Collections;
import java.util.Set;

/** Filter for {@code GET /executions?tags=...&status=...&tenant=...}. */
public final class ExecutionQuery {

    private final Set<String> tags;
    private final ExecutionStatus status;
    private final String tenantId;

    public ExecutionQuery(Set<String> tags, ExecutionStatus status, String tenantId) {
        this.tags = tags == null ? Collections.emptySet() : tags;
        this.status = status;
        this.tenantId = tenantId;
    }

    public Set<String> getTags() {
        return tags;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public String getTenantId() {
        return tenantId;
    }
}
