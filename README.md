Java agents with a Spring Shell CLI (Janus, data analysis, SWE, tool-call).

## Documentation

| Topic | 中文 | English |
|-------|------|---------|
| **Shell 用法与案例** | [SHELL.md](shell/docs/SHELL.md) | [SHELL.en.md](shell/docs/SHELL.en.md) |
| **Agent 框架与流程** | [AGENT-FLOW.md](core/docs/AGENT-FLOW.md) | [AGENT-FLOW.en.md](core/docs/AGENT-FLOW.en.md) |
| **FAQ** | [FAQ.md](docs/FAQ.md) | [FAQ.en.md](docs/FAQ.en.md) |

Quick start: from repo root, `mvn -pl core install -DskipTests && mvn -f shell/pom.xml spring-boot:run`, then e.g. `da request -p "…"`.
