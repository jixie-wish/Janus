# janus-shell Usage

> [中文](SHELL.md) · Agent internals: [core/docs/AGENT-FLOW.en.md](../../core/docs/AGENT-FLOW.en.md)

The `shell` module is Janus’s CLI entry point. After startup you get a `shell:>` prompt and run the ToolCall agent on SenseNova via `tool-call request`.

---

## Requirements

- **JDK 21**
- **Maven 3.6.3+**
- API key and model in `shell/src/main/resources/application.properties`

---

## Start

Run from the **Janus root**. If you changed `core`, install it before starting:

```bash
mvn -pl core install -DskipTests
mvn -f shell/pom.xml spring-boot:run
```

### Linux / macOS (bash / zsh)

```bash
cd /path/to/Janus

export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"

mvn -pl core install -DskipTests
mvn -f shell/pom.xml spring-boot:run
```

### Windows (PowerShell)

```powershell
cd C:\path\to\Janus

$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

mvn -pl core install -DskipTests
mvn -f shell/pom.xml spring-boot:run
```

### Windows (CMD)

```cmd
cd C:\path\to\Janus

set JAVA_HOME=C:\path\to\jdk-21
set PATH=%JAVA_HOME%\bin;%PATH%

mvn -pl core install -DskipTests
mvn -f shell/pom.xml spring-boot:run
```

When you see `shell:>`, the app is ready.

---

## Commands

### tool-call request

```text
tool-call request --prompt "<task>" [--model sensenova] [--conversation-id <id>]
```

| Option | Short | Required | Default | Description |
|--------|-------|----------|---------|-------------|
| `--prompt` | `-p` | yes | — | User message to the agent |
| `--model` | `-m` | no | `sensenova` | CLI model alias (maps to configured ChatModel) |
| `--conversation-id` | `-c` | no | — | Reuse in-process memory across requests in the same shell session |

Examples:

```text
shell:> tool-call request -p "hello"
shell:> tool-call request --prompt "Describe Janus in one sentence" -m sensenova
shell:> tool-call request -p "Tell me about China" -c demo
shell:> tool-call request -p "What did I ask just now" -c demo
```

With `-c`, the first line of output echoes `conversation-id: ...`. Memory lasts only for the **current shell process** (lost after exit).

Clear cached session:

```text
shell:> tool-call clear-session -c demo
```

Output is multi-line text such as `Step 1: ...`, `Step 2: ...` (per-step agent output).

### Other shell commands

```text
shell:> help
shell:> help tool-call
shell:> clear
shell:> exit
```

---

## Non-interactive (scripts / CI)

**Linux / macOS**

```bash
mvn -f shell/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="tool-call request --prompt hello --spring.shell.interactive.enabled=false"
```

**Windows (PowerShell)**

```powershell
mvn -f shell/pom.xml spring-boot:run `
  "-Dspring-boot.run.arguments=tool-call request --prompt hello --spring.shell.interactive.enabled=false"
```

**Windows (CMD)**

```cmd
mvn -f shell/pom.xml spring-boot:run -Dspring-boot.run.arguments="tool-call request --prompt hello --spring.shell.interactive.enabled=false"
```

---

## Configuration

File: `shell/src/main/resources/application.properties`

| Property | Description |
|----------|-------------|
| `spring.ai.openai.api-key` | SenseNova API key |
| `spring.ai.openai.base-url` | Usually `https://token.sensenova.cn/v1` |
| `spring.ai.openai.chat.model` | Model id, e.g. `sensenova-6.7-flash-lite` |
| `janus.agent.max-steps` | Max steps per run (default 30) |
| `spring.shell.interactive.enabled` | `true` for interactive `shell:>` |

Use `spring.ai.openai.chat.model` on Spring AI 2.x; avoid deprecated `chat.options.model`.

Do not commit real API keys; use `application-local.properties` if gitignored.

---

## API check (optional)

Without starting shell:

```bash
cd model-verify
python3 sensenova-6.7-flash-lite.py
python3 sensenova-6.7-flash-lite.py --prompt "hello"
```

(On Windows, use `python` if `python3` is not available.)

---

## Troubleshooting

| Symptom | What to try |
|---------|-------------|
| `engine is not available temporarily` | Often transient API/model; compare with `model-verify`; check `chat.model` |
| core changes not reflected | `mvn -pl core install -DskipTests`, restart shell |
| Wrong/missing Java | JDK 21 + `JAVA_HOME` |
| Repeated greetings, no stop | Model did not call `terminate`; see [core docs](../../core/docs/AGENT-FLOW.en.md) |
