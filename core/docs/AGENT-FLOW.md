# ToolCallAgent

> [EN](AGENT-FLOW.en.md) · `S` `U` `A` `T`

```mermaid
stateDiagram-v2
    direction TB

    [*] --> IDLE
    IDLE --> RUNNING: run

    state RUNNING {
        [*] --> Mem: [S,U₀]

        Mem --> Step: loop

        state Step {
            [*] --> U: +Uₙ
            U --> A: 模型→Aₙ
            A --> M: +Aₙ

            M --> T0: 无 tool
            M --> T1: 有 tool

            T0 --> U
            T1 --> T: +Tₙ
            T --> Term?: terminate
            Term? --> U: 否
        }

        Step --> DONE: terminate
        Step --> Mem: maxSteps
    }

    RUNNING --> FINISHED: terminate
    RUNNING --> ERROR: 异常
    FINISHED --> IDLE
    ERROR --> IDLE
    RUNNING --> IDLE: finally
```
