-- Seeds a real docscan-style deployment with the OCR-accounting graph (examples/graphs/ocr-accounting.json)
-- and its reflectively-loadable processors (examples/processors/docscan-processors.json). Run
-- docscan-schema.sql first.
--
-- Two of the graph's processors are intentionally NOT seeded here: docscan-classify and
-- llm-prompt-prepare each need a live, injected dependency (a classifier client / a prompt
-- template lookup), so they can't be instantiated reflectively from a ProcessorDefinition row -
-- the application constructs them itself and registers them via
-- AgentsGraphEngine.registerProcessor(...) at startup.

DELETE FROM agentsgraph_processor;
INSERT INTO agentsgraph_processor (id, name, is_external, instance_class, params) VALUES
    ('docscan-ocr', 'docscan-ocr', true, 'org.webvane.docscan.graph.processor.DocscanOcrProcessor',
     '{"processUrl": "http://10.64.0.40:8109/process/{template}"}'),
    ('docscan-ocr-visualize', 'docscan-ocr-visualize', false, 'org.webvane.docscan.graph.processor.OcrVisualizationProcessor',
     '{}'),
    ('text-llm', 'text-llm', true, 'org.webvane.docscan.graph.processor.TextLlmProcessor',
     '{"processUrl": "http://10.64.0.39:8095/completion"}'),
    ('llm-completion', 'llm-completion', true, 'org.webvane.docscan.graph.processor.LlmCompletionProcessor',
     '{"processUrl": "http://10.64.0.39:8095/completion"}'),
    ('llm-response-parse', 'llm-response-parse', false, 'org.webvane.docscan.graph.processor.LlmResponseParsingProcessor',
     '{}'),
    ('fallback-answer', 'fallback-answer', false, 'org.webvane.docscan.graph.processor.FallbackAnswerProcessor',
     '{}');

DELETE FROM agentsgraph_graph_config;
INSERT INTO agentsgraph_graph_config (id, name, description, config, created_at, updated_at) VALUES
    ('ocr-accounting', 'OCR for accounting', 'Обработка первички для бухгалтерии', '{
  "id": "ocr-accounting",
  "version": "v5",
  "templates": ["file:accountant", "file:default"],
  "entry_node_id": "intent_router",
  "nodes": [
    {
      "id": "intent_router",
      "routing_strategy": "rules",
      "routing_table": {
        "hasFile==true": "edge_classify",
        "default": "edge_text_llm"
      },
      "fallback_edge_id": "edge_error_fallback"
    },
    {
      "id": "classify_router",
      "routing_strategy": "rules",
      "routing_table": {
        "default": "edge_ocr_pipeline"
      },
      "fallback_edge_id": "edge_error_fallback"
    }
  ],
  "edges": [
    {
      "id": "edge_classify",
      "steps": [
        {
          "id": "step_classify",
          "processor_id": "docscan-classify",
          "output_to_next": ["documentType", "confidence", "cachedOcrJson"],
          "output_to_save": ["documentType", "confidence"]
        }
      ],
      "tags_to_add": ["classified"],
      "next_node_id": "classify_router"
    },
    {
      "id": "edge_ocr_pipeline",
      "steps": [
        {
          "id": "step_ocr",
          "processor_id": "docscan-ocr",
          "params": {"processUrl": "http://docscan-service/process/{template}"},
          "output_to_next": ["json"],
          "output_to_save": ["json"]
        },
        {
          "id": "step_ocr_visualize",
          "processor_id": "docscan-ocr-visualize",
          "output_to_next": ["json", "textItems", "tables", "bodyItems"],
          "output_to_save": ["textItems", "tables", "bodyItems"]
        },
        {
          "id": "step_llm_prompt",
          "processor_id": "llm-prompt-prepare",
          "output_to_next": ["prompt", "promptHeader", "promptName"],
          "output_to_save": []
        },
        {
          "id": "step_llm_call",
          "processor_id": "llm-completion",
          "params": {"processUrl": "http://llm-service/completion"},
          "output_to_next": ["llmContent", "promptHeader", "promptName"],
          "output_to_save": ["llmContent"]
        },
        {
          "id": "step_llm_parse",
          "processor_id": "llm-response-parse",
          "output_to_save": ["summary", "llmBodyItems"]
        }
      ],
      "tags_to_add": ["ocr_processed"],
      "next_node_id": null
    },
    {
      "id": "edge_text_llm",
      "steps": [
        {
          "id": "step_text_llm",
          "processor_id": "text-llm"
        }
      ],
      "tags_to_add": ["text_processed"],
      "next_node_id": null
    },
    {
      "id": "edge_error_fallback",
      "steps": [
        {
          "id": "step_fallback",
          "processor_id": "fallback-answer"
        }
      ],
      "tags_to_add": ["needs_review"],
      "next_node_id": null
    }
  ]
}', EXTRACT(EPOCH FROM NOW())::BIGINT * 1000, EXTRACT(EPOCH FROM NOW())::BIGINT * 1000);
