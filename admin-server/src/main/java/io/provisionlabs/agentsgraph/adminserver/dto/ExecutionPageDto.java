package io.provisionlabs.agentsgraph.adminserver.dto;

import java.util.List;

/** One page of the executions table, sorted by start date. */
public final class ExecutionPageDto {

    private final List<ExecutionDto> items;
    private final int page;
    private final int size;
    private final long total;

    public ExecutionPageDto(List<ExecutionDto> items, int page, int size, long total) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.total = total;
    }

    public List<ExecutionDto> getItems() {
        return items;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotal() {
        return total;
    }
}
