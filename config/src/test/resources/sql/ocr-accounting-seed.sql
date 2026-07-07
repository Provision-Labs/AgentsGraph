-- H2-compatible seed data for tests, mirroring examples/sql/docscan-seed-data.sql (Postgres) but
-- with literal timestamps instead of EXTRACT(EPOCH FROM NOW()) for portability.

INSERT INTO agentsgraph_processor (id, name, is_external, instance_class, params) VALUES
    ('docscan-ocr', 'docscan-ocr', true, 'org.webvane.pipes.DocScanOcrProcessor',
     '{"processUrl": "http://10.64.0.40:8109"}'),
    ('llm-postprocessor', 'llm-postprocessor', true, 'org.webvane.pipes.LLMPostProcessor',
     '{"processUrl": "http://10.64.0.39:8095"}');

INSERT INTO agentsgraph_graph_config (id, name, description, config, created_at, updated_at) VALUES
    ('ocr-accounting', 'OCR for accounting', 'Обработка первички для бухгалтерии', '{
  "id": "ocr-accounting",
  "version": "v1",
  "templates": ["file:accountant", "file:default"],
  "entry_node_id": "intent_router",
  "nodes": [
    {
      "id": "intent_router",
      "type": "router",
      "routing_strategy": "rules",
      "routing_table": {"default": "edge_ocr_pipeline"},
      "fallback_edge_id": "edge_ocr_pipeline"
    }
  ],
  "edges": [
    {
      "id": "edge_ocr_pipeline",
      "steps": [
        {
          "id": "step_ocr",
          "processor_id": "docscan-ocr",
          "params": {"processUrl": "http://docscan-service/process/{template}"},
          "output_to_next": ["json"],
          "output_to_save": ["tables", "textItems", "json"]
        },
        {
          "id": "step_llm",
          "processor_id": "llm-postprocessor",
          "params": {"processUrl": "http://docscan-service/process/{template}"},
          "output_to_save": ["tables", "textItems", "json"]
        }
      ],
      "tags_to_add": ["ocr_processed"]
    }
  ]
}', 1700000000000, 1700000000000);
