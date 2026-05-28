# Janus FAQ

> [中文](FAQ.md) · Shell: [shell/docs/SHELL.en.md](../shell/docs/SHELL.en.md) · Framework: [core/docs/AGENT-FLOW.en.md](../core/docs/AGENT-FLOW.en.md)

---

## Shell and workspace

| Symptom | Cause / fix |
|---------|-------------|
| Files under `shell/workspace/da/...` | Process started under `shell/`; relative `workspace/da` resolves to CWD. Start from **repo root**, or set absolute `janus.agent.da.workspace-root`. |
| `-c` does not remember | Same shell process and `-m` required; memory cleared on exit. |
| core changes ignored | `mvn -pl core install -DskipTests`, restart shell. |

---

## Agent behavior

| Symptom | Notes |
|---------|-------|
| Last step only shows `terminate` | `terminate` ends the run; user-facing text should come from `create_chat_completion` or the agent’s main tools. |
| 3–4 steps per request | Model may answer then terminate, or treat long next-step text as a new task. |
| `da` chart errors | Run `npm install` in `core/chart-visualization`; check paths under workspace. |
| `swe` commands interfere | Same `-c` shares one bash session; use a new `-c` or `clear-session`. |

---

## Memory and multi-step behavior

| Symptom | Cause / fix |
|---------|-------------|
| Second `-c` prompt treats “continue” or system text as user input | Stale **session partition** from before optimizations; run `<group> clear-session -c <id>`. See [AGENT-FLOW.en.md](../core/docs/AGENT-FLOW.en.md#memory-and-multi-step-optimizations). |
| Many steps, repeated similar replies | `nextStepPrompt` is **ephemeral**; under the `create_chat_completion` contract, assistant-only drafts are not stored and ephemeral reminders are injected. |
| Continuation lacks prior step detail | **By design**: session stores 2 summary messages per prompt, not full `Step 1…n` traces. |
| No `-c` but still “remembers” | Confirm you did not reuse `-c`; ephemeral runs clear the context partition on exit. |
| Odd behavior after upgrading core | Restart shell and `clear-session` for affected `-c` ids. |

---

## Model and build

| Symptom | Fix |
|---------|-----|
| `engine is not available temporarily` | Transient API/model issue; check `spring.ai.openai.*`. |
| Maven plugin unresolved in IDE | Use `.mvn/settings.xml` or a mirror. |
| Missing `com.wish:core` | `mvn install -DskipTests` from repo root. |

---

## Quick checks

```text
shell:> tool-call request -p "hello" -m sensenova
shell:> da request -p "list files in workspace/da" -m sensenova -c test
```

API without shell: `cd model-verify && python3 sensenova-6.7-flash-lite.py --prompt "hello"`
