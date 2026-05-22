# Agent Architecture and Flow

> [中文](AGENT-FLOW.md) · Shell module: [shell/docs/SHELL.en.md](../../shell/docs/SHELL.en.md) · `S` `U` `A` `T`

Inheritance: `BaseAgent` ← `ReactAgent` ← `ToolCallAgent` (created and run via [shell/docs/SHELL.en.md](../../shell/docs/SHELL.en.md) `ToolCallService`).

```mermaid
flowchart BT
    BA[BaseAgent abstract]
    RA[ReactAgent abstract]
    TCA[ToolCallAgent]
    BA --> RA --> TCA
```

---

## BaseAgent

Abstract base: state machine, `run` loop, memory bootstrap, stuck handling. Subclasses implement `step()`.

### Architecture

```mermaid
classDiagram
    class BaseAgent {
        <<abstract>>
        #LLMChatClient chatClient
        #int maxSteps
        #AgentState agentState
        #String systemPrompt
        #String nextStepPrompt
        #String lastThinkResult
        #int stuckThreshold
        +run(conversation, request) String
        +step(conversation)* String
        #handleStuck()
        +healthCheck() boolean
    }

    class AgentState {
        <<enum>>
        IDLE
        RUNNING
        FINISHED
        ERROR
    }

    class LLMChatClient {
        +addSystemMemory()
        +addUserMemory()
        +isStuck()
    }

    BaseAgent --> AgentState
    BaseAgent --> LLMChatClient
```

| Role | Description |
|------|-------------|
| `run` | Public entry; drives `IDLE→RUNNING→…` |
| Memory init | `S` then `U₀` (fixed order) |
| Loop | `step()` up to `maxSteps`; `isStuck` after each step |
| `step` | Abstract; defined by subclasses |

### Flow

```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> RUNNING: run()

    state RUNNING {
        [*] --> Init: Memory [S,U₀]
        Init --> Loop: step()
        Loop --> Loop: not done
        Loop --> Stuck: isStuck?
        Stuck --> Loop: append recovery prompt
    }

    RUNNING --> FINISHED: set by subclass
    RUNNING --> ERROR: exception
    FINISHED --> IDLE: finally
    ERROR --> IDLE: finally
    RUNNING --> IDLE: finally
```

---

## ReactAgent

ReAct skeleton on top of `BaseAgent`: `step = think → act?`.

### Architecture

```mermaid
classDiagram
    class BaseAgent {
        <<abstract>>
    }
    class ReactAgent {
        <<abstract>>
        +step(conversation) String
        +think(conversation)* boolean
        +act(conversation)* String
    }
    ReactAgent --|> BaseAgent
```

| Method | Role |
|--------|------|
| `think` | Reason; returns whether to call `act` |
| `act` | Execute; returns step result string |
| `step` | If `think` is false → `"Thinking complete…"` |

### Flow

```mermaid
stateDiagram-v2
    [*] --> Think

    Think --> Act: think() == true
    Think --> Skip: think() == false

    Act --> [*]: act() result
    Skip --> [*]: no-action message

    note right of Think
        implemented by
        ToolCallAgent
    end note
```

---

## ToolCallAgent

Concrete agent: call model with tools, parse `Aₙ`, execute or echo. Default in Janus.

### Architecture

```mermaid
classDiagram
    class ReactAgent {
        <<abstract>>
    }
    class ToolCallAgent {
        -ToolCallingManager toolCallingManager
        -ChatResponse currentChatResponse
        -List~ToolCall~ currentToolCalls
        +think(conversation) boolean
        +act(conversation) String
    }
    class ChatLifecycleTool {
        +create_chat_completion()
        +terminate()
    }
    class LLMChatClient {
        +askWithTools()
        +addMemory()
        +replaceMemory()
    }

    ToolCallAgent --|> ReactAgent
    ToolCallAgent --> ChatLifecycleTool : registered tools
    ToolCallAgent --> LLMChatClient : via BaseAgent
```

| Piece | Description |
|-------|-------------|
| `ChatLifecycleTool` | `create_chat_completion`, `terminate` exposed to model |
| `think` | `askWithTools` → persist `Aₙ` |
| `act` | Echo text if no tools; else `replaceMemory` |

### Flow

```mermaid
stateDiagram-v2
    direction TB

    state think {
        [*] --> U: Memory += Uₙ
        U --> API: Prompt + tools
        API --> A: model→Aₙ
        A --> M: Memory += Aₙ
    }

    [*] --> think
    think --> Branch: toolCalls?

    Branch --> Echo: empty · has text → act echo
    Branch --> Skip: empty · no text
    Branch --> Tools: non-empty

    state act_tools {
        [*] --> Exec
        Exec --> T: Memory += Tₙ replace
        T --> Term: terminate?
    }

    Tools --> act_tools
    Term --> FINISHED: yes
    Term --> [*]: no → next step
    Echo --> [*]
    Skip --> [*]
```

**Memory per step**

```text
[S,U₀] → +Uₙ → Aₙ → +Aₙ → (+Tₙ if tools)
```

| `toolCalls` | Memory after step | Ends run? |
|-------------|-------------------|-----------|
| `[]` + text | `…,Uₙ,Aₙ` | no |
| includes `terminate` | `…,Uₙ,Aₙ,Tₙ` | yes |

---

## Entry point

See [shell/docs/SHELL.en.md](../../shell/docs/SHELL.en.md): `ToolCallService.run` → `ToolCallAgent` → `agent.run`.
