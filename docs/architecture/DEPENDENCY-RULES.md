# Dependency Rules

1. shared must not depend on any business module.
2. project/conversation/memory/knowledge must not depend on runtime.
3. inference must not depend on any other business module.
4. tooling must not directly access conversation/project persistence.
5. Cross-module access uses public Application APIs or Events.
6. Never access another module's Mapper/DAO/Repository implementation.
7. Domain code must not depend on LangChain4j, LangGraph, Temporal or provider SDKs.
8. Runtime orchestrates; domain modules do not know Runtime.
9. PostgreSQL/Git/Temporal are sources of truth for their respective state.
10. Redis must never be the only durable state store.
11. AgentDefinition must not own Project/Task/Workspace/Conversation/AgentRun/Checkpoint state.
12. HTTP controllers must use public Application APIs and never repositories/persistence.
13. TypeScript orchestration and Python Sandbox workers must not import or own business persistence clients.
14. Production PostgreSQL mode must reject noop/deterministic execution adapters and unsafe development secrets.
15. Official MCP Registry responses are untrusted Tooling inputs: snapshot first, require explicit
    SystemAdministrator review, and never consult the Registry in installation or execution paths.
16. Project-mode scope is `Project -> ProjectDirectory -> Conversation`; a Coding Run must pin the
    exact directory-bound Workspace and cannot infer or replace it from a later HTTP request.
17. Agent owns one mutable current configuration. Runtime must copy it into one immutable per-Run
    snapshot at admission; an existing Run must never re-read mutable Agent configuration.
