package com.provisionlabs.agentsgraph.control;

import com.provisionlabs.agentsgraph.context.ExecutionContext;
import com.provisionlabs.agentsgraph.trace.TraceRecord;

import java.util.List;

/**
 * Layer 5 - Control Plane &amp; Analytics: operational interfaces built on top of the trace store
 * (query executions, replay/debug a flow from a given node, feed dashboards).
 */
public interface ControlPlane {

    List<TraceRecord> queryExecutions(ExecutionQuery query);

    /** Returns the context snapshot recorded for {@code flowId} right after {@code fromNodeId} ran. */
    ExecutionContext replay(String flowId, String fromNodeId);
}
