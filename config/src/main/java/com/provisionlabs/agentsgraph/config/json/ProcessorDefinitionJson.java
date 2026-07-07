package com.provisionlabs.agentsgraph.config.json;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jackson-friendly mirror of a {@code plb_pipeline_processor} row: {@code params} is accepted as
 * either a nested JSON object or a raw JSON string (as it is stored in the legacy {@code TEXT}
 * database column), see {@link PipelineJsonMapper#toProcessorDefinition}.
 */
public class ProcessorDefinitionJson {

    public String id;
    public String name;

    @JsonProperty("is_external")
    public boolean isExternal;

    @JsonProperty("instance_class")
    public String instanceClass;

    public Object params;
}
