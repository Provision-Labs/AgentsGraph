# AgentsGraph
AgentsGraph: Declarative AI orchestration where Nodes decide &amp; Edges execute pipelines. Config-driven graph architecture mapped to Agent Loops (Observe→Plan→Act→Reflect). Enterprise-ready, observable, and hot-reloadable via DB. Define complex agentic workflows in JSON or visually.

graph TD
    User[Input Data] --> C[🧠 Node: Classifier]
    C -->|Observe + Plan| D{Routing Table}
    D -->|route_A| E[️ Edge: Pipeline A]
    D -->|route_B| F[🛤️ Edge: Pipeline B]
    D -->|fallback| G[🛤️ Edge: Error Handler]
    E -->|Act + Reflect| H[✅ Result + Tags]
    F -->|Act + Reflect| H
    G -->|Act + Reflect| H


