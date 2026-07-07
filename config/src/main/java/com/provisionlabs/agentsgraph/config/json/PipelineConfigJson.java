package com.provisionlabs.agentsgraph.config.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson-friendly mirror of the legacy docscan-pipeline {@code PipelineConfig} JSON shape:
 * <pre>{@code
 * {
 *   "id": "ocr-accounting",
 *   "name": "...",
 *   "templates": ["file:accountant", "file:default"],
 *   "steps": [
 *     {"processorId": "docscan-ocr", "params": {...}, "outputToNext": ["json"], "outputToSave": ["tables"]}
 *   ]
 * }
 * }</pre>
 * Kept as a plain, mutable POJO (public fields, default constructor) so Jackson can (de)serialize
 * it without extra configuration; {@link PipelineJsonMapper} converts to/from the framework's own
 * {@link com.provisionlabs.agentsgraph.config.GraphDefinition}.
 */
public class PipelineConfigJson {

    public String id;
    public String name;
    public List<String> templates = new ArrayList<>();
    public List<StepConfigJson> steps = new ArrayList<>();

    public static class StepConfigJson {
        public String processorId;
        public Map<String, Object> params = new LinkedHashMap<>();
        public List<String> outputToNext = new ArrayList<>();
        public List<String> outputToSave = new ArrayList<>();
    }
}
