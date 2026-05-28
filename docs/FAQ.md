# Janus 常见问题

> [English](FAQ.en.md) · Shell：[shell/docs/SHELL.md](../shell/docs/SHELL.md) · 框架：[core/docs/AGENT-FLOW.md](../core/docs/AGENT-FLOW.md)

---

## Shell 与 workspace

| 现象 | 原因 / 处理 |
|------|-------------|
| 文件出现在 `shell/workspace/da/...` | 在 `shell/` 子目录启动了进程；相对路径 `workspace/da` 解析到当前工作目录。应从 **Janus 根目录** 启动，或配置绝对路径 `janus.agent.da.workspace-root`。 |
| `-c` 续聊无效 | 需同一 Shell 进程、同一 `-m`；退出后记忆丢失。 |
| 修改 core 未生效 | `mvn -pl core install -DskipTests` 后重启 shell。 |

---

## Agent 行为

| 现象 | 说明 |
|------|------|
| 最后一步只有 `terminate` 文案 | `terminate` 是结束信号；用户可见答案应来自 `create_chat_completion`（或各 Agent 的主工具输出）。 |
| 出现 3～4 个 Step | 模型可能先作答再 `terminate`，或把较长的 next-step 提示当成新任务；可缩短 Agent 的 next-step 配置。 |
| `da` 出图失败 | 在 `core/chart-visualization` 执行 `npm install`；确认 JSON/CSV 路径在对应 workspace 下。 |
| `swe` 命令互相影响 | 同一 `-c` 共用 bash 会话；换会话用新 `-c` 或 `clear-session`。 |

---

## 记忆与多步行为

| 现象 | 原因 / 处理 |
|------|-------------|
| 带 `-c` 第二轮把「继续」「系统提示」当用户输入 | 多为 **session 分区** 在优化前写入了步内引导；执行 `<group> clear-session -c <id>` 后重试。详见 [AGENT-FLOW.md](../core/docs/AGENT-FLOW.md#记忆与多步优化optimization)。 |
| 多步空转、每步重复类似回答 | `nextStepPrompt` 已改为 **ephemeral** 不落库；若模型连续只输出正文不调工具，`ToolCallAgent` 会跳过入库并注入 ephemeral 提醒（`create_chat_completion` 合约，Janus/tool-call 默认开启）。 |
| 续聊看不到上一轮详细 step | **设计如此**：session 只存每轮 2 条摘要，不存完整 `Step 1…n` 轨迹；需要细节请在同一 prompt 内完成或让 Agent 写入文件。 |
| 无 `-c` 仍感觉「记得」上次 | 检查是否误用了相同 `-c`；无 `-c` 时 `runEphemeral` 会在结束时 `chatMemory.clear`。 |
| 升级 core 后续聊异常 | 旧进程内 Session 对象与 ChatMemory 分区可能不兼容新摘要逻辑；重启 Shell 并对相关 `-c` 执行 `clear-session`。 |

---

## 模型与构建

| 现象 | 处理 |
|------|------|
| `engine is not available temporarily` | API/模型临时不可用；检查 `spring.ai.openai.*` 配置。 |
| IDE 报 Maven 插件解析失败 | 使用项目 `.mvn/settings.xml` 或阿里云镜像；见下文 Maven 备注。 |
| 找不到 `com.wish:core` | 在仓库根目录 `mvn install -DskipTests` 或 `mvn -pl shell -am install`。 |

### Maven 备注

- 将 `.mvn/settings.xml` 复制到 `~/.m2/settings.xml`，或在 IDE 中指定该文件。
- 删除 `~/.m2/repository` 下相关 `*.lastUpdated` 后重试。

---

## 快速验证

```text
shell:> tool-call request -p "你好" -m sensenova
shell:> da request -p "列出 workspace/da 下文件" -m sensenova -c test
```

API 连通性（不启动 shell）：`cd model-verify && python3 sensenova-6.7-flash-lite.py --prompt "你好"`
