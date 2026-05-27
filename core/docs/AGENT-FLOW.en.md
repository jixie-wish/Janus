# Agent Architecture and Flow

> [中文](AGENT-FLOW.md) · FAQ: [docs/FAQ.en.md](../../docs/FAQ.en.md)

**Reading order**: [Terminology](#terminology) → [Notation](#notation) → [UserContext](#usercontext) → Agent / ToolCall → [Flow](#flow-multi-agent-orchestration) → [Typical usage](#typical-usage).

**Inheritance**: `BaseAgent` ← `ReactAgent` ← `ToolCallAgent` (entry point `agent.run(context, prompt)`). Orchestration: `BaseFlow` ← `PlanningFlow`.

```mermaid
flowchart TB
    subgraph flowLayer["Orchestration"]
        BF[BaseFlow]
        PF[PlanningFlow]
        BF --> PF
    end

    subgraph agentLayer["Executors · multiple instances"]
        A1["Agent<br/>key: research"]
        A2["Agent<br/>key: default"]
        A3["Agent<br/>key: …"]
    end

    subgraph coreLayer["core inheritance"]
        BA[BaseAgent] --> RA[ReactAgent] --> TCA[ToolCallAgent]
    end

    PF ==>|run per step| A1
    PF ==>|run per step| A2
    PF -.-> A3
    A1 & A2 & A3 -.->|implements| TCA
```

---

## Terminology

| Term | Definition |
|------|------------|
| **Agent** | Object that runs a multi-step task via `run(context, request)` and `step(context)`; includes a state machine (`AgentState`), prompts, tools, and `maxSteps`. |
| **BaseAgent / ReactAgent / ToolCallAgent** | Inheritance chain: run loop → ReAct (think/act) → Spring AI tool-calling implementation. |
| **UserContext** | Session carrier for one logical conversation: conversation id, `ChatMemory` reference, step count, and optional per-step scratch state. |
| **BaseUserContext** | Generic UserContext: partition read/write, `isStuck`, etc. |
| **ToolCallUserContext** | UserContext for `ToolCallAgent`; holds per-step tool-call fields. |
| **LLMChatClient** | Stateless model access: `askWithTools(context, …)` builds a prompt from context and calls `ChatModel`. |
| **ChatModel** | Spring AI LLM backend (e.g. SenseNova OpenAI-compatible API). |
| **ChatMemory** | Component that stores `Message` lists keyed by `conversation` (e.g. `MessageWindowChatMemory`). |
| **conversation** | Id on UserContext used as the ChatMemory partition key; set by the caller when creating the context (e.g. `demo`, `conv-1`). |
| **run** | Agent entry: `run(BaseUserContext, request)` loops `step` until finished or `maxSteps`. |
| **step** | One agent iteration; in `ReactAgent`, `think` then optional `act`. |
| **think / act** | ReAct phases: `think` calls the model; `act` runs tools or returns text. |
| **Flow** | Multi-agent orchestration (e.g. `PlanningFlow`: plan → execute steps → finalize). |

---

## Notation

Message sequences and diagrams use the following symbols (`n` = step index in the agent loop, starting at 1; `0` = initial user message in `run`).

| Symbol | Meaning | Spring AI type |
|--------|---------|----------------|
| **S** | System prompt (written once per partition on first run) | `SystemMessage` |
| **U₀** | User message from `request` in `run(context, request)` | `UserMessage` |
| **Uₙ** | User-side prompt before step `n` think (e.g. `nextStepPrompt`) | `UserMessage` |
| **Aₙ** | Assistant reply at step `n` | `AssistantMessage` |
| **Tₙ** | Tool result at step `n` (often after `replaceMemory`) | `ToolResponseMessage` |

**One ToolCallAgent step with tools**:

```text
…, Uₙ → Aₙ → Tₙ
```

**Typical prefix after first `run` on a partition**:

```text
[S, U₀] → … further steps …
```

`Memory +=` in diagrams means append or replace messages in the current `conversation` partition via UserContext.

---

## UserContext

### Definition

**UserContext** encapsulates state for **one logical conversation** when invoking an agent: partition name (`conversation`), `ChatMemory` reference, step counter, and subclass fields (e.g. tool-call scratch).

Standard call:

```text
agent.run(userContext, request)
```

`request` is the user text for this turn; history is read and updated through the ChatMemory partition referenced by `userContext`.

### Design rationale

| Rationale | Description |
|-----------|-------------|
| Multi-session isolation | Multiple conversations in one process, each with its own UserContext and Memory partition, sharing one agent instance. |
| Agent / data separation | Agent defines behavior; session data lives on UserContext + ChatMemory for reuse and testing. |
| Stateless client | `LLMChatClient` has no built-in memory; each call passes UserContext explicitly. |

### Component roles

| Component | Responsibility | Session state |
|-----------|----------------|---------------|
| **Agent** | `run` / `step`, think/act, termination | None (only runtime fields such as `AgentState`) |
| **LLMChatClient** | Prompt assembly, `ChatModel` calls, tools | None |
| **ChatMemory** | Persist `Message` lists | Per `conversation` partition |
| **UserContext** | Bind partition, step count, per-step scratch; memory API | Yes |

```text
request ──► UserContext
                 ├── ChatMemory[conversation]  ← S, U, A, T
                 └── Agent.run(context, request)
                          └── LLMChatClient.askWithTools(context, …) ──► ChatModel
```

### Implementation types

| Type | Use case | Main fields / behavior |
|------|----------|------------------------|
| **`BaseUserContext`** | Generic agents; Flow planning turns | `conversation`, `chatMemory`, `currentStep`; `addUserMemory`, `getAllMessages`, `isStuck` |
| **`ToolCallUserContext`** | `ToolCallAgent` | Extends base; `currentChatResponse`, `currentToolCalls`; `clearStepState()` after each act |

Factory: `BaseAgent.createUserContext(conversationId, chatMemory)`; `ToolCallAgent` returns `ToolCallUserContext`. `PlanningFlow.setupExecutors` calls this per executor.

### Lifecycle and reuse

| Mode | Practice |
|------|----------|
| One-shot | `new ToolCallUserContext(conversationId, chatMemory)` → `agent.run(context, prompt)`; optionally `chatMemory.clear(conversationId)` afterward. |
| Resume in-process | Reuse the same `ToolCallUserContext` (or same `conversation` on shared `ChatMemory`) across multiple `run` calls. |
| Parallel threads | Multiple `conversation` partitions; one `ToolCallAgent` may `run` against different contexts. |

Creating, caching, and disposing contexts is the **integrator’s** responsibility; core does not define a process-wide session map.

### Class structure

```mermaid
classDiagram
    class BaseUserContext {
        +String conversation
        +ChatMemory chatMemory
        +int currentStep
        +addSystemMemory()
        +addUserMemory()
        +getAllMessages()
        +isStuck()
    }
    class ToolCallUserContext {
        +ChatResponse currentChatResponse
        +Prompt currentChatPrompt
        +List currentToolCalls
        +clearStepState()
    }
    class LLMChatClient {
        <<stateless>>
        +askWithTools(context, ...)
    }

    BaseUserContext <|-- ToolCallUserContext
    LLMChatClient ..> BaseUserContext : history read/write via context
```

---

## BaseAgent

Base class for all agents: **`run(context, request)`** multi-step loop (state machine, writes **S** / **U₀** via context, `step(context)`, `isStuck`). Requires a UserContext matching the agent type (see [UserContext](#usercontext)).

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
        +run(context, request) String
        +step(context)* String
        +createUserContext(id, memory) BaseUserContext
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

    class BaseUserContext {
        +conversation
        +chatMemory
    }

    class LLMChatClient {
        <<stateless>>
        +askWithTools(context, ...)
    }

    BaseAgent --> AgentState
    BaseAgent --> LLMChatClient
    BaseAgent ..> BaseUserContext : passed to run
```

| Role | Description |
|------|-------------|
| `run` | `run(BaseUserContext, request)`; drives `IDLE→RUNNING→…` |
| Memory init | Via context: `S`, then `U` when `request` is non-blank |
| Loop | `step(context)` up to `maxSteps`; `context.isStuck` after each step |
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
        +step(context) String
        +think(context)* boolean
        +act(context)* String
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
        +think(context) boolean
        +act(context) String
        +createUserContext() ToolCallUserContext
    }
    class ToolCallUserContext {
        +currentChatResponse
        +currentToolCalls
    }
    class ChatLifecycleTool {
        +create_chat_completion()
        +terminate()
    }
    class LLMChatClient {
        +askWithTools(context, ...)
    }

    ToolCallAgent --|> ReactAgent
    ToolCallAgent --> ChatLifecycleTool : registered tools
    ToolCallAgent --> LLMChatClient : via BaseAgent
    ToolCallAgent ..> ToolCallUserContext : per-step fields
```

| Piece | Description |
|-------|-------------|
| `ChatLifecycleTool` | `create_chat_completion`, `terminate` exposed to model |
| `think` | `askWithTools(context, …)` → `Aₙ` on context memory partition; step fields on `ToolCallUserContext` |
| `act` | Echo text if no tools; else `context.replaceMemory` |

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

## Flow (multi-agent orchestration)

Flows live in **core**, aligned with OpenManus `app.flow`: orchestrate **plan → execute steps → finalize** on top of multiple `BaseAgent` instances; each step still uses an agent’s `run` / ReAct loop.

Construct `PlanningFlow` and `PlanningFlowUserContext`, then call `execute(context, input)`; single-agent use cases still call `agent.run` directly.

**Diagram legend**: **solid arrows** = calls / control; **dashed arrows** = data (messages, plan state, partition refs).

### BaseFlow: multiple agents

`BaseFlow` holds `Map<String, BaseAgent> agents`; `getExecutor(stepType)` picks one agent per plan step (from `[agent_key]` or `executorKeys`).

```mermaid
flowchart TB
    subgraph BaseFlow["BaseFlow"]
        LC["llmChatClient"]
        MAP["agents<br/>Map String → BaseAgent"]
        KEYS["executorKeys"]
        PRI["primaryAgentName"]
        GET["getExecutor(stepType)"]
    end

    subgraph registered["Multiple agent instances (example)"]
        direction LR
        AR["research<br/>ToolCallAgent"]
        AD["default<br/>ToolCallAgent"]
        AC["coder<br/>ToolCallAgent"]
    end

    MAP --> AR & AD & AC
    GET --> MAP
    PRI -.->|fallback| AD
    KEYS -.->|order if no type| MAP
```

### PlanningFlow: component topology

```mermaid
flowchart TB
    subgraph caller["Caller"]
        APP["execute(ctx, input)"]
    end

    subgraph PF["PlanningFlow"]
        direction TB
        PFCore["PlanningFlow"]
        PT["PlanTool<br/>plans → Plan"]
        PCM["planningChatMemory"]
        LC["LLMChatClient"]
    end

    subgraph agents["agents (one box per agent)"]
        direction LR
        AG1["Agent<br/>key: research"]
        AG2["Agent<br/>key: default"]
    end

    subgraph ctx["PlanningFlowUserContext"]
        PID["planId"]
        subgraph partitions["flowContext · one UserContext per agent"]
            C0["…_executor_0"]
            C1["…_executor_1"]
        end
    end

    MEM_EX[("ChatMemory<br/>ctx.chatMemory<br/>executor sub-sessions only")]
    MEM_PLAN[("ChatMemory<br/>planningChatMemory<br/>planning phases only")]
    CM["ChatModel API"]
    PLAN[("Plan<br/>steps / status")]

    APP ==>|call| PFCore
    PFCore --> PT
    PFCore --> PCM
    PCM -.-> MEM_PLAN
    PFCore --> LC
    LC ==>|HTTP| CM

    PFCore ==>|setupExecutors| partitions
    AG1 -.->|sub-session| C0
    AG2 -.->|sub-session| C1
    C0 -.->|partition executor_0| MEM_EX
    C1 -.->|partition executor_1| MEM_EX
    PT -.-> PLAN

    PFCore ==>|getExecutor + run| AG1
    PFCore ==>|getExecutor + run| AG2
```

### PlanningFlow: control flow

```mermaid
sequenceDiagram
    autonumber
    participant Caller
    participant PF as PlanningFlow
    participant Ctx as PlanningFlowUserContext
    participant LC as LLMChatClient
    participant PT as PlanTool
    participant Plan as Plan
    participant A1 as Agent research
    participant A2 as Agent default

    Caller->>PF: execute(ctx, input)
    PF->>Ctx: setupExecutors(agents.values)
    Note over Ctx: one UserContext + partition per agent<br/>messages never mixed across agents

    rect rgb(240,248,255)
        Note over PF,PT: Phase: create plan
        PF->>LC: askWithTools(planningContext, …)
        LC->>PT: planning(create, plan_id, steps)
        PT->>Plan: createPlan
    end

    loop until no active step
        rect rgb(255,250,240)
            Note over PF,A2: Phase: execute step
            PF->>Plan: getCurrentStepInfo(planId)
            Plan-->>PF: stepIndex, type, text
            alt type = research
                PF->>A1: run(executorContext₀, stepPrompt)
                A1-->>PF: stepResult
            else type = default or empty
                PF->>A2: run(executorContext₁, stepPrompt)
                A2-->>PF: stepResult
            end
            PF->>PT: markStep(COMPLETED)
        end
    end

    rect rgb(240,255,240)
        Note over PF,LC: Phase: finalize
        PF->>LC: askWithTools(finalizeContext, …)
        LC-->>PF: summary
    end

    PF-->>Caller: aggregated output
```

### PlanningFlow: data flow

```mermaid
flowchart LR
    subgraph in["Input"]
        TASK["input task"]
        PID_IN["ctx.planId"]
    end

    subgraph planStore["Plan data · in-memory PlanTool"]
        STEPS["steps<br/>[research] …<br/>[default] …"]
        STATUS["stepStatuses"]
    end

    subgraph memPlan["planningChatMemory"]
        M_CREATE["…_planning_create"]
        M_FIN["…_planning_finalize"]
    end

    subgraph memExec["ctx.chatMemory · executor sub-sessions"]
        M_EX0["…_executor_0"]
        M_EX1["…_executor_1"]
    end

    subgraph out["Output"]
        RES["execute return string"]
    end

    TASK -.-> M_CREATE
    PID_IN -.-> planStore
    M_CREATE -.->|planning tool| STEPS
    STEPS -.->|getCurrentStepInfo| EX["executeStep"]
    EX -.->|stepPrompt → that agent's partition only| M_EX0
    EX -.->|stepPrompt → that agent's partition only| M_EX1
    M_EX0 & M_EX1 -.->|after run| STATUS
    STATUS -.->|done| M_FIN
    M_FIN -.-> RES
    EX -.-> RES
```

### Class relationships

```mermaid
classDiagram
    class BaseFlow {
        <<abstract>>
        #Map~String,BaseAgent~ agents
        #LLMChatClient llmChatClient
        +getExecutor(stepType) BaseAgent
        +execute(context, input)* String
    }
    class PlanningFlow {
        -PlanTool planTool
        -ChatMemory planningChatMemory
        +execute(PlanningFlowUserContext, input) String
    }
    class BaseFlowUserContext {
        +String conversation
        +ChatMemory chatMemory
        +setupExecutors(agents)
        +getExecutorContext(agent) BaseUserContext
    }
    class PlanningFlowUserContext {
        +String planId
    }
    class PlanTool {
        +plan(command, planId, ...)
        +createPlan(...)
        +markStep(...)
    }
    class Plan {
        +getCurrentStepInfo()
        +parseStepType(step)
    }

    BaseFlow <|-- PlanningFlow
    BaseFlowUserContext <|-- PlanningFlowUserContext
    PlanningFlow --> PlanTool
    PlanTool --> Plan
    PlanningFlow --> BaseAgent : run per step
    BaseFlowUserContext --> BaseUserContext : one per executor
```

| Class | Role |
|-------|------|
| `BaseFlow` | Register agents, primary agent, `getExecutor(stepType)` |
| `PlanningFlow` | Create plan, loop steps, `finalizePlan` |
| `BaseFlowUserContext` | Flow `conversation`; `setupExecutors` builds one `UserContext` per executor (separate **sub-session partition**, same `chatMemory` ref) |
| `PlanningFlowUserContext` | Adds `planId` (default `{conversation}_plan`) and plan lifecycle flags |
| `PlanTool` | In-memory plans; `planning` tool for the planning LLM call |
| `Plan` | Steps, statuses, `getCurrentStepInfo()` |

### Execution phases (branches)

```mermaid
flowchart TB
    IN["execute(context, input)"]
    SETUP["setupExecutors"]
    CREATE["createInitialPlan"]
    CHECK{"hasPlan?"}
    FAIL["return failed"]
    LOOP["getCurrentStepInfo"]
    DONE{"no active step?"}
    FINAL["finalizePlan"]
    EXEC["getExecutor → Agent.run"]
    MARK["markStep COMPLETED"]
    EARLY{"Agent FINISHED?"}

    IN ==> SETUP ==> CREATE ==> CHECK
    CHECK -->|no| FAIL
    CHECK -->|yes| LOOP ==> DONE
    DONE -->|yes| FINAL ==> OUT["return"]
    DONE -->|no| EXEC ==> MARK ==> EARLY
    EARLY -->|yes| OUT
    EARLY -->|no| LOOP
```

| Phase | Behavior |
|-------|----------|
| **Create plan** | Separate `planningChatMemory`; `askWithTools` + `PlanTool`; prompt requires matching `plan_id`. On failure, `createDefaultPlan` (three placeholder steps). |
| **Execute step** | `getCurrentStepInfo()` picks first active step, marks `in_progress`; builds step prompt for `executor.run`. |
| **Pick executor** | Optional `[agent_name]` in step text → `type` → `getExecutor(type)` (case-insensitive key); else `executorKeys` / primary. |
| **Finish** | No active steps → `finalizePlan`; or break early if executor hits `FINISHED`. |

### Plan and PlanTool

**`plan_id`**: required for every `planning` command except `list` (no “active plan” shortcut).

**Steps**: `List<String>`; prefix with **`[agent_key]`** when multiple agents are registered.

**Step statuses**: `not_started` · `in_progress` · `completed` · `blocked` (see `Plan.StepStatus`).

`getCurrentStepInfo()` returns `(stepIndex, {text, type?})`, or `(-1, {})` when done.

**PlanTool commands**: `create` · `update` · `list` · `get` · `mark_step` · `delete`.

### Context and executor sub-sessions (Memory)

**Different executor agents do not share one message history.** Implementation has two layers:

| Layer | Description |
|-------|-------------|
| **ChatMemory instance** | Executors use **`PlanningFlowUserContext.chatMemory`**. Plan create/finalize use **`PlanningFlow.planningChatMemory`** (a separate instance). Messages are not stored in the same object across those two roles. |
| **conversation partition (sub-session)** | Within one `ChatMemory` instance, each partition is a separate logical thread. `getAllMessages()` only reads the current context’s partition—**never another agent’s**. |

**Executor sub-sessions** (`setupExecutors`, shared `ctx.chatMemory` reference, non-overlapping partitions):

| Partition | Bound to | Contents |
|-----------|----------|----------|
| `{conversation}_executor_0` | First agent’s `UserContext` | That agent’s **S / U / A / T** across `run` calls |
| `{conversation}_executor_1` | Second agent’s `UserContext` | Isolated from `_executor_0` |
| … | `conversation + "_executor_" + i` | One partition per agent box |

`setupExecutors` calls `agent.createUserContext(executorConv, chatMemory)` (`ToolCallAgent` → `ToolCallUserContext`).

**Planning-phase sessions** (`planningChatMemory`, separate from executor storage):

| Partition | Phase |
|-----------|-------|
| `{conversation}_planning_create` | `createInitialPlan` |
| `{conversation}_planning_finalize` | `finalizePlan` |

```text
PlanningFlowUserContext.chatMemory          PlanningFlow.planningChatMemory
 ├── conv_executor_0  ← Agent A read/write only   ├── conv_planning_create
 ├── conv_executor_1  ← Agent B read/write only   └── conv_planning_finalize
```

When step type routes to `research`, only that agent’s `_executor_k` partition grows; other executor partitions are unchanged until their steps run.

### Relation to single-agent runs

```text
PlanningFlow.execute(context, userTask)
  ├─ createInitialPlan  → LLMChatClient + PlanTool + planningChatMemory
  └─ each executeStep  → BaseAgent.run(executorContext, stepPrompt)
```

Flow adds planning and step dispatch on top of `BaseAgent.run`; each step still runs the full agent loop.

---

## Typical usage

### Single agent (ToolCall)

```java
ChatMemory memory = MessageWindowChatMemory.builder().build();
LLMChatClient client = new LLMChatClient(chatModel, List.of());
ToolCallAgent agent = new ToolCallAgent(client, maxSteps);

ToolCallUserContext ctx = new ToolCallUserContext("conv-1", memory);
String out = agent.run(ctx, "user task");
```

```text
createUserContext / new ToolCallUserContext
  → agent.run(context, request)
       → step → think → LLMChatClient.askWithTools(context, …)
       → act  → tools / terminate
```

### Multiple agents (PlanningFlow)

```java
PlanningFlow flow = new PlanningFlow(
        llmChatClient,
        Map.of("research", researchAgent, "default", defaultAgent),
        "default");
PlanningFlowUserContext ctx = new PlanningFlowUserContext("conv-1", sharedChatMemory);
String result = flow.execute(ctx, "complete the task");
```

See [Flow (multi-agent orchestration)](#flow-multi-agent-orchestration).
