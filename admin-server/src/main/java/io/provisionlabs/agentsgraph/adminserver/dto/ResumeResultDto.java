package io.provisionlabs.agentsgraph.adminserver.dto;

/**
 * Outcome of a resume: the NEW flow's id (lineage back to the original lives in its metadata as
 * {@code parent_flow_id}/{@code resumed_from_seq}) and its final status. When the resumed run
 * failed hard (the failure propagated), {@code flowId} is {@code null} and {@code error} carries
 * the message - the failed attempt is still traced under its own (new) flow id in the store.
 */
public final class ResumeResultDto {

    private final String flowId;
    private final String status;
    private final String error;

    public ResumeResultDto(String flowId, String status, String error) {
        this.flowId = flowId;
        this.status = status;
        this.error = error;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
