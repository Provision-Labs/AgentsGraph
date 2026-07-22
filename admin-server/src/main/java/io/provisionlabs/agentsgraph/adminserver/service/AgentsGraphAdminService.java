package io.provisionlabs.agentsgraph.adminserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.provisionlabs.agentsgraph.AgentsGraphEngine;
import io.provisionlabs.agentsgraph.adminserver.AgentsGraphAdminController;
import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import io.provisionlabs.agentsgraph.trace.ExecutionStatus;
import io.provisionlabs.agentsgraph.trace.StepTraceRecord;
import io.provisionlabs.agentsgraph.trace.TraceRecord;
import io.provisionlabs.agentsgraph.adminserver.dto.ExecutionDto;
import io.provisionlabs.agentsgraph.adminserver.dto.GraphSummaryDto;
import io.provisionlabs.agentsgraph.adminserver.dto.ProcessorDto;
import io.provisionlabs.agentsgraph.adminserver.dto.ResumeResultDto;
import io.provisionlabs.agentsgraph.adminserver.dto.StepDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-model (and resume entry point) behind the AgentsGraph UI - a plain-Java facade over the
 * engine's own stores, so it works against whatever {@code ConfigStore}/{@code
 * ProcessorDefinitionStore}/{@code TraceStore} implementations the engine was built with
 * (JDBC-backed in production: {@code agentsgraph_graph_config}/{@code agentsgraph_processor}/
 * {@code agentsgraph_execution_trace}/{@code agentsgraph_step_trace}). No Spring types here -
 * {@link AgentsGraphAdminController} is the thin HTTP skin.
 */
public final class AgentsGraphAdminService {

    private final AgentsGraphEngine engine;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentsGraphAdminService(AgentsGraphEngine engine) {
        this.engine = engine;
    }

    /** Every deployed graph ({@code agentsgraph_graph_config}), summarized for the list view. */
    public List<GraphSummaryDto> listGraphs() {
        return engine.getConfigStore().findAll().stream()
                .map(graph -> new GraphSummaryDto(graph.getId(), graph.getVersion(),
                        graph.getEntryNodeId(), graph.getNodes().size(), graph.getEdges().size()))
                .collect(Collectors.toList());
    }

    /** The graph's full native JSON (nodes/edges/routing) - what the UI's canvas renders. */
    public String getGraphJson(String graphId) {
        return engine.dumpGraphJson(graphId);
    }

    /** Every {@code agentsgraph_processor} row. */
    public List<ProcessorDto> listProcessors() {
        return engine.getProcessorDefinitionStore().findAll().stream()
                .map(definition -> toDto(definition, false))
                .collect(Collectors.toList());
    }

    /**
     * The processors a graph actually references (in step order of its edges): each ref resolved
     * against {@code agentsgraph_processor}; a ref with no row is a programmatic processor
     * (registered in code) and is reported with {@code programmatic=true}.
     */
    public List<ProcessorDto> listGraphProcessors(String graphId) {
        GraphDefinition graph = engine.getConfigStore().getGraph(graphId);
        Map<String, ProcessorDefinition> definitionsById = engine.getProcessorDefinitionStore().findAll()
                .stream().collect(Collectors.toMap(ProcessorDefinition::getId, d -> d, (a, b) -> a));

        Set<String> referencedRefs = new LinkedHashSet<>();
        for (EdgeDefinition edge : graph.getEdges().values()) {
            for (StepDefinition step : edge.getSteps()) {
                referencedRefs.add(step.getProcessorRef());
            }
        }

        List<ProcessorDto> processors = new ArrayList<>();
        for (String ref : referencedRefs) {
            ProcessorDefinition definition = definitionsById.get(ref);
            processors.add(definition != null
                    ? toDto(definition, false)
                    : new ProcessorDto(ref, ref, false, null, Map.of(), true));
        }
        return processors;
    }

    /** Flows from {@code agentsgraph_execution_trace}; {@code null} filters mean "any". */
    public List<ExecutionDto> listExecutions(String status, String tenantId) {
        ExecutionStatus statusFilter = status == null || status.isBlank()
                ? null : ExecutionStatus.valueOf(status.trim().toUpperCase());
        return engine.getTraceStore().query(null, statusFilter, emptyToNull(tenantId)).stream()
                .map(AgentsGraphAdminService::toDto)
                .collect(Collectors.toList());
    }

    public ExecutionDto getExecution(String flowId) {
        TraceRecord record = engine.getTraceStore().find(flowId)
                .orElseThrow(() -> new IllegalArgumentException("No execution trace for flow '" + flowId + "'"));
        return toDto(record);
    }

    /** The plain-text one-flow report ({@code AgentsGraphEngine.describeFlow}). */
    public String getExecutionReport(String flowId) {
        return engine.describeFlow(flowId);
    }

    /** The flow's {@code agentsgraph_step_trace} rows (debug runs only), in execution order. */
    public List<StepDto> listSteps(String flowId) {
        return engine.getStepTraces(flowId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public StepDto getStep(String flowId, long seq) {
        return engine.getTraceStore().findStep(flowId, seq)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No step trace for flow '" + flowId + "' seq " + seq));
    }

    /**
     * Re-runs the flow from the recorded step on the recorded data (plus {@code overrides}, when
     * given) - see {@code AgentsGraphEngine.resumeFrom}. A hard failure of the resumed run is
     * reported in the result rather than thrown, so the UI can show it inline; the failed attempt
     * is still traced in the store under its own new flow id.
     */
    public ResumeResultDto resume(String flowId, long seq, Map<String, Object> overrides) {
        try {
            ExecutionContext resumed = engine.resumeFrom(flowId, seq, overrides == null ? Map.of() : overrides);
            String status = engine.getTraceStore().find(resumed.getFlowId())
                    .map(record -> record.getStatus().name())
                    .orElse(ExecutionStatus.COMPLETED.name());
            return new ResumeResultDto(resumed.getFlowId(), status, null);
        } catch (IllegalArgumentException | IllegalStateException precondition) {
            // Unknown step / non-restartable snapshot: the caller's mistake - let the controller
            // translate it into a 4xx.
            throw precondition;
        } catch (RuntimeException resumedRunFailure) {
            return new ResumeResultDto(null, ExecutionStatus.FAILED.name(), resumedRunFailure.getMessage());
        }
    }

    private ProcessorDto toDto(ProcessorDefinition definition, boolean programmatic) {
        return new ProcessorDto(definition.getId(), definition.getName(), definition.isExternal(),
                definition.getInstanceClass(), definition.getParams(), programmatic);
    }

    private static ExecutionDto toDto(TraceRecord record) {
        return new ExecutionDto(record.getFlowId(), record.getTenantId(), record.getStatus().name(),
                record.getTags(), record.getError(), record.getTelemetry().getStepCount(),
                record.getTelemetry().getTokenCost(), record.getTelemetry().getDurationMs(),
                record.getTelemetry().getRetryAttempts());
    }

    private StepDto toDto(StepTraceRecord record) {
        return new StepDto(record.getFlowId(), record.getSeq(), record.getGraphId(),
                record.getGraphVersion(), record.getNodeId(), record.getEdgeId(), record.getStepId(),
                record.getStepIndex(), record.getProcessorRef(),
                parseJson(record.getInputContextJson()), parseJson(record.getOutputJson()),
                record.getStatus().name(), record.getError(), record.isRestartable(),
                record.getStartedAtMillis(), record.getDurationMs());
    }

    /** Recorded snapshots are JSON strings; the UI wants real JSON structures. */
    private Object parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            // A snapshot that somehow isn't valid JSON is still worth showing raw.
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("__raw__", json);
            return fallback;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
