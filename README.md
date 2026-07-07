# AgentsGraph
AgentsGraph: Declarative AI orchestration where Nodes decide &amp; Edges execute pipelines. Config-driven graph architecture mapped to Agent Loops (Observe→Plan→Act→Reflect). Enterprise-ready, observable, and hot-reloadable via DB. Define complex agentic workflows in JSON or visually.

flowchart TD
    A["1️⃣ Config Store<br/>(Declarative Registry)"] --> B["2️⃣ Execution Context<br/>(Immutable State)"]
    B --> C["3️⃣ Runtime Orchestrator<br/>(Node → Edge Engine)"]
    C --> D["4️⃣ Status & Trace Store<br/>(Time-Series + Tags)"]
    D --> E["5️⃣ Control Plane & Analytics<br/>(Query • Replay • Dashboards)"]
