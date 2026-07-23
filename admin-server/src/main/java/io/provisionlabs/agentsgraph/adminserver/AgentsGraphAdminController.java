package io.provisionlabs.agentsgraph.adminserver;

import io.provisionlabs.agentsgraph.adminserver.dto.ExecutionDto;
import io.provisionlabs.agentsgraph.adminserver.dto.ExecutionPageDto;
import io.provisionlabs.agentsgraph.adminserver.dto.GraphSummaryDto;
import io.provisionlabs.agentsgraph.adminserver.dto.ProcessorDto;
import io.provisionlabs.agentsgraph.adminserver.dto.ResumeRequest;
import io.provisionlabs.agentsgraph.adminserver.dto.ResumeResultDto;
import io.provisionlabs.agentsgraph.adminserver.dto.StepDto;
import io.provisionlabs.agentsgraph.adminserver.service.AgentsGraphAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST skin over {@link AgentsGraphAdminService} for the AgentsGraph UI:
 *
 * <pre>
 * GET  /api/agentsgraph/graphs                                  - graph list
 * GET  /api/agentsgraph/graphs/{id}                             - full graph JSON (canvas)
 * GET  /api/agentsgraph/graphs/{id}/processors                  - processors the graph references
 * GET  /api/agentsgraph/processors                              - every agentsgraph_processor row
 * GET  /api/agentsgraph/executions?status=&amp;tenantId=         - execution traces
 * GET  /api/agentsgraph/executions/{flowId}                     - one execution
 * GET  /api/agentsgraph/executions/{flowId}/report              - plain-text describeFlow report
 * GET  /api/agentsgraph/executions/{flowId}/steps               - debug step trace
 * GET  /api/agentsgraph/executions/{flowId}/steps/{seq}         - one step (parsed in/out)
 * POST /api/agentsgraph/executions/{flowId}/steps/{seq}/resume  - re-run from the step
 * </pre>
 */
@RestController
@RequestMapping("/api/agentsgraph")
public class AgentsGraphAdminController {

    private final AgentsGraphAdminService service;

    public AgentsGraphAdminController(AgentsGraphAdminService service) {
        this.service = service;
    }

    @GetMapping("/graphs")
    public List<GraphSummaryDto> listGraphs() {
        return service.listGraphs();
    }

    @GetMapping(value = "/graphs/{graphId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getGraphJson(@PathVariable("graphId") String graphId) {
        return service.getGraphJson(graphId);
    }

    @GetMapping("/graphs/{graphId}/processors")
    public List<ProcessorDto> listGraphProcessors(@PathVariable("graphId") String graphId) {
        return service.listGraphProcessors(graphId);
    }

    @GetMapping("/processors")
    public List<ProcessorDto> listProcessors() {
        return service.listProcessors();
    }

    @GetMapping("/executions")
    public ExecutionPageDto listExecutions(@RequestParam(name = "status", required = false) String status,
                                            @RequestParam(name = "tenantId", required = false) String tenantId,
                                            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                                            @RequestParam(name = "size", required = false, defaultValue = "20") int size,
                                            @RequestParam(name = "order", required = false, defaultValue = "desc") String order) {
        return service.listExecutions(status, tenantId, page, size, order);
    }

    @GetMapping("/executions/{flowId}")
    public ExecutionDto getExecution(@PathVariable("flowId") String flowId) {
        return service.getExecution(flowId);
    }

    @GetMapping(value = "/executions/{flowId}/report", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getExecutionReport(@PathVariable("flowId") String flowId) {
        return service.getExecutionReport(flowId);
    }

    @GetMapping("/executions/{flowId}/steps")
    public List<StepDto> listSteps(@PathVariable("flowId") String flowId) {
        return service.listSteps(flowId);
    }

    @GetMapping("/executions/{flowId}/steps/{seq}")
    public StepDto getStep(@PathVariable("flowId") String flowId, @PathVariable("seq") long seq) {
        return service.getStep(flowId, seq);
    }

    @PostMapping("/executions/{flowId}/steps/{seq}/resume")
    public ResumeResultDto resume(@PathVariable("flowId") String flowId, @PathVariable("seq") long seq,
                                   @RequestBody(required = false) ResumeRequest request) {
        return service.resume(flowId, seq, request == null ? Map.of() : request.getOverrides());
    }

    /** Unknown graph/flow/step (or a non-debug flow): the id is wrong, not the server. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> notFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** Precondition failures, e.g. resuming a non-restartable step snapshot. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
