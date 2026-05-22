# Janus FAQ (troubleshooting)

> [中文](FAQ.md) · Shell: [shell/docs/SHELL.en.md](../shell/docs/SHELL.en.md) · Agent flow: [core/docs/AGENT-FLOW.en.md](../core/docs/AGENT-FLOW.en.md)

Summary of common ToolCall Agent / Shell behavior and fixes (including notes from 2026-05-22).

---

## 1. `terminate` and Step output

- `terminate` returns a **session-end signal**, not the user-facing answer. Use `create_chat_completion` for the answer first.
- Old `extractActResult` scanned **full** tool history and duplicated Step 1 text in Step 2. Current code matches **this step’s** `currentToolCalls` only (aligned with OpenManus).

## 2. `extractActResult`

Builds the step result from tools executed in **this** `act()` (by tool-call id), sets `FINISHED` on `terminate`, and logs those tool results.

## 3. Why 3–4 steps?

- **3 steps**: answer via tool → plain assistant text (no `terminate` yet) → `terminate`.
- **4 steps** (long `nextStep`): model treats each injected `Decide the single best action...` **User** line as a new task and calls `create_chat_completion` again. OpenManus uses a much shorter default next-step prompt.

## 4. `nextStep` injection

Added before every **`think()`**, not before `act()`. System + user `-p` are added once at `run()` start (system skipped on continuing conversations).

## 5. Prompt layering

- **System**: role and general rules.
- **Next step**: per-turn action (short; avoid looking like a new user question).
- **Tool descriptions**: per-tool semantics.

## 6. `conversation-id` (`-c`)

- Without `-c`: new agent + memory per request.
- With `-c`: reuse in-process memory in the same shell session; lost after exit.
- `tool-call clear-session -c <id>` drops cache.

## 7. “What did I ask just now?” without memory

The model may echo **only the current prompt** (“just now” = this message), not a prior turn. That is not the same as remembering an earlier question (e.g. “Tell me about China”). Use `-c` for multi-turn CLI memory.

## 8. OpenManus comparison

Same two lifecycle tools and next-step-as-user pattern; Janus Shell isolates requests unless `-c` is set. OpenManus can retain memory across `run()` on the same agent instance.

## 9. Quick tests

See [FAQ.md §9](FAQ.md#9-推荐验证命令) (Chinese section with command examples).

## 10. Possible follow-ups

Shorter next-step prompt, hide `terminate` text in Shell output, skip redundant text-only steps, persistent sessions.
