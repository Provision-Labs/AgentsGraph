package io.provisionlabs.agentsgraph.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** In-memory {@link StepTraceStore}; the default when no persistent store is wired in. */
public final class InMemoryStepTraceStore implements StepTraceStore {

    private final Map<String, List<StepTraceRecord>> recordsByFlow = new ConcurrentHashMap<>();

    @Override
    public void append(StepTraceRecord record) {
        recordsByFlow.computeIfAbsent(record.getFlowId(), id -> new CopyOnWriteArrayList<>()).add(record);
    }

    @Override
    public List<StepTraceRecord> findByFlow(String flowId) {
        return recordsByFlow.getOrDefault(flowId, List.of()).stream()
                .sorted(Comparator.comparingLong(StepTraceRecord::getSeq))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<StepTraceRecord> find(String flowId, long seq) {
        return recordsByFlow.getOrDefault(flowId, List.of()).stream()
                .filter(record -> record.getSeq() == seq)
                .findFirst();
    }

    @Override
    public long deleteOlderThan(long startedBeforeEpochMillis) {
        long removed = 0;
        for (Map.Entry<String, List<StepTraceRecord>> entry : recordsByFlow.entrySet()) {
            List<StepTraceRecord> stale = new ArrayList<>();
            for (StepTraceRecord record : entry.getValue()) {
                if (record.getStartedAtMillis() < startedBeforeEpochMillis) {
                    stale.add(record);
                }
            }
            entry.getValue().removeAll(stale);
            removed += stale.size();
        }
        recordsByFlow.values().removeIf(List::isEmpty);
        return removed;
    }
}
