# Agent 架构与流程

> [English](AGENT-FLOW.en.md) · 符号：`S` system · `U` user · `A` assistant · `T` tool result

继承关系：`BaseAgent` ← `ReactAgent` ← `ToolCallAgent`（shell 里 `ToolCallService` 创建并 `run`）。

```mermaid
flowchart BT
    BA[BaseAgent abstract]
    RA[ReactAgent abstract]
    TCA[ToolCallAgent]
    BA --> RA --> TCA
```

---

## BaseAgent

抽象基类：状态机、`run` 循环、memory 初始化、stuck 处理。子类实现 `step()`。

### 架构

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

| 职责 | 说明 |
|------|------|
| `run` | 唯一对外入口，管理 `IDLE→RUNNING→…` |
| memory 初始化 | 先 `S`，再 `U₀`（顺序固定） |
| 循环 | `step()` × `maxSteps`，步末 `isStuck` |
| `step` | 抽象，由子类定义 |

### Flow

```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> RUNNING: run()

    state RUNNING {
        [*] --> Init: Memory [S,U₀]
        Init --> Loop: step()
        Loop --> Loop: 未结束
        Loop --> Stuck: isStuck?
        Stuck --> Loop: 追加恢复 prompt
    }

    RUNNING --> FINISHED: 子类置位
    RUNNING --> ERROR: 异常
    FINISHED --> IDLE: finally
    ERROR --> IDLE: finally
    RUNNING --> IDLE: finally
```

---

## ReactAgent

在 `BaseAgent` 上固定 **ReAct 骨架**：`step = think → act?`。

### 架构

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

| 方法 | 作用 |
|------|------|
| `think` | 推理；返回是否进入 `act` |
| `act` | 执行；返回本步结果字符串 |
| `step` | `think` 为 false 时直接返回 `"Thinking complete…"` |

### Flow

```mermaid
stateDiagram-v2
    [*] --> Think

    Think --> Act: think() == true
    Think --> Skip: think() == false

    Act --> [*]: act() 结果
    Skip --> [*]: 无 action 文案

    note right of Think
        子类实现
        ToolCallAgent
    end note
```

---

## ToolCallAgent

具体实现：调模型（带 tools）、解析 `Aₙ`、执行或回显。Janus 默认使用的 agent。

### 架构

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
    ToolCallAgent --> ChatLifecycleTool : 注册为 tools
    ToolCallAgent --> LLMChatClient : 经 BaseAgent
```

| 组件 | 说明 |
|------|------|
| `ChatLifecycleTool` | 模型可见的 `create_chat_completion`、`terminate` |
| `think` | `askWithTools` → 写 `Aₙ` 入 memory |
| `act` | 无 tool 回显 text；有 tool 则 `replaceMemory` |

### Flow

```mermaid
stateDiagram-v2
    direction TB

    state think {
        [*] --> U: Memory += Uₙ
        U --> API: Prompt + tools
        API --> A: 模型→Aₙ
        A --> M: Memory += Aₙ
    }

    [*] --> think
    think --> Branch: toolCalls?

    Branch --> Echo: 空 · 有 text → act 回显
    Branch --> Skip: 空 · 无 text
    Branch --> Tools: 非空

    state act_tools {
        [*] --> Exec
        Exec --> T: Memory += Tₙ replace
        T --> Term: terminate?
    }

    Tools --> act_tools
    Term --> FINISHED: 是
    Term --> [*]: 否 → 下一步
    Echo --> [*]
    Skip --> [*]
```

**Memory 一步**

```text
[S,U₀] → +Uₙ → Aₙ → +Aₙ → (+Tₙ 若有 tool)
```

| `toolCalls` | 本步后 Memory | 会结束吗 |
|-------------|---------------|----------|
| `[]` + text | `…,Uₙ,Aₙ` | 否 |
| 含 `terminate` | `…,Uₙ,Aₙ,Tₙ` | 是 |

---

## 入口

`ToolCallService.run` → `new ToolCallAgent(llmChatClient, maxSteps)` → `agent.run(conversationId, prompt)`。
