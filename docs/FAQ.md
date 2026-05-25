# Janus 常见问题与排查（FAQ）

> [English](FAQ.en.md) · Shell 用法：[shell/docs/SHELL.md](../shell/docs/SHELL.md) · Agent 流程：[core/docs/AGENT-FLOW.md](../core/docs/AGENT-FLOW.md)

本文整理 ToolCall Agent 与 Shell 使用中的典型现象、原因与当前实现约定（含 2026-05-22 调试结论）。

---

## 1. `terminate` 与 Step 输出

### 现象

- Shell 最后一步出现：`The interaction has been completed with status: success`，像「没回答用户问题」。
- 两步流程时 Step 2 又重复 Step 1 的长文，并带上结束句。

### 原因

- `terminate` 的返回值是**结束信号**，设计上当 `create_chat_completion` 已交付答案后使用。
- 理想路径：`create_chat_completion`（用户可见答案）→ 下一步 `terminate`（结束）。
- 旧版 `extractActResult` 遍历 **full history** 里所有工具结果，导致 Step 2 把 Step 1 的 `create_chat_completion` 再拼一遍。

### 当前实现

- `extractActResult` 只处理**本步** `think()` 里 `currentToolCalls` 对应的工具响应（按 tool-call `id` 匹配），与 OpenManus「只拼本步 `tool_calls` 结果」一致。
- `terminate` 文案仍会出现在**本步** Shell 输出中（若该步只调了 `terminate`）。

---

## 2. `extractActResult` 在做什么？

**本意**：本次 `act()` 执行了几个 tool，就把这几个 tool 的返回按 `think()` 中的调用顺序拼成字符串，作为本步 `Step N:` 的内容。

**还会**：

- 遇到 `terminate` 时 `transitState(FINISHED)`，结束 `run()` 循环；
- 打日志 `Tool xxx result: ...`（仅本步匹配到的工具）。

**不是**：扫描整段对话里历史上所有 `ToolResponseMessage`（那是旧实现，已改掉）。

---

## 3. 为什么有的请求要 3～4 个 Step？

### 典型三步（旧 prompt / 弱约束）

| Step | 常见行为 |
|------|----------|
| 1 | `create_chat_completion` 作答 |
| 2 | **无工具**，仅 assistant 正文（口头总结） |
| 3 | `terminate` |

Step 2 出现是因为：`tool_choice=AUTO` 下，无 tool 但有正文时 `think()` 仍返回 true，会进入 `act()` 回显 `lastThinkResult`。模型未在 Step 1 后立刻 `terminate`，属于**模型选择**，不是框架强制。

### 典型四步（当前较长 `nextStep`）

问 *Describe tools you can support* 时：

| Step | 行为 |
|------|------|
| 1 | 正确列出 `create_chat_completion` / `terminate` |
| 2～3 | 又把每轮注入的 **nextStep**（以 `Decide the single best action...` 开头）当成**新的用户任务**，重复 `create_chat_completion` |
| 4 | `terminate` |

**根因**：每个 `think()` 前把 `NEXT_STEP_PROMPT` 当作 **User 消息** 追加进 memory（与 OpenManus 相同机制），长文案易被模型当成新指令，且与「already answered → terminate」规则冲突。

OpenManus 默认 `NEXT_STEP_PROMPT` 仅一句 *use terminate to stop*，较少触发此类循环。

---

## 4. 每步都会加 `nextStep` 吗？

**是**，但仅加在每次 **`think()`** 之前，**不是**整个 `step` 开头，**`act()` 不会**追加。

`run()` 开头只加一次 **system** + 用户 **request**（`-p`）。续聊会话时 **不会**重复追加 system（见 `hasConversation()`）。

---

## 5. 提示词写在哪里合适？

| 位置 | 适合 |
|------|------|
| **System** | 稳定角色、语言、通用原则；不宜写死工具名（尤其将来有 MCP 时） |
| **Next step** | 本轮动作提醒（答完是否 `terminate`）；宜**短**，避免像用户新问题 |
| **`@Tool` description** | 每个工具的语义（答案工具 vs 结束工具） |

Janus 曾把完整流程写进 System，后改为：**短 System + 较长 Next step**；Next step 过长会导致上文第四节的多步循环。

---

## 6. Shell 会话与 `conversation-id`

### 不传 `-c`

每次 `tool-call request`：

- 新建 `LLMChatClient` + `ToolCallAgent`；
- 内部随机 `conversationId`；
- **不**写入进程内缓存。

**两次命令互不相干**（与 OpenManus 每次新进程类似；若在同一 agent 实例上多次 `run()` 则会累积 memory，Janus Shell 默认不复用 agent）。

### 传 `-c demo`

- 同一 Shell 进程内复用 `demo`（按 `model:conversationId` 缓存）；
- 第二次可引用第一次用户问题与工具结果；
- 退出 Shell 后缓存丢失（未持久化）。

```text
tool-call request -p "Tell me about China" -c demo
tool-call request -p "What question did I ask you just now" -c demo
tool-call clear-session -c demo
```

带 `-c` 时输出首行会回显 `conversation-id: ...`。

---

## 7. 「刚才问了什么」— 有/无 memory 的差异

### 有 `-c demo`（有历史）

能答：**上一题是 "Tell me about China"**（memory 里确有上一轮 User + 工具答）。

### 无 `-c`（空会话）

模型仍可能答：**你刚才问的是 "What question did I ask you just now"**。

这不是记住了 China，而是把英文 **just now** 理解成**当前这条输入本身**，无需历史即可复述字面意思。

若从对话逻辑期望「没有上一题」，更合理的答法应是：*这是本会话第一条消息，此前没有其它问题。* 需要在 prompt 中明确：**仅一条用户消息时，不要将 nextStep 或当前句当作「上一轮用户问题」**。

---

## 8. 与 OpenManus 的异同（摘要）

| 项目 | OpenManus `ToolCallAgent` | Janus |
|------|---------------------------|--------|
| 默认工具 | `CreateChatCompletion` + `Terminate` | 同 |
| 每步 nextStep 作 User 注入 | 是 | 是 |
| `act` 拼工具结果 | 仅本步 `tool_calls` | 本步 `currentToolCalls`（按 id） |
| 默认 prompt | 极短 | 可配置，曾加长 nextStep |
| 跨多次 CLI | 新进程无记忆；同 agent 多次 `run` 可累积 | Shell：`-c` 进程内复用；否则每次新建 |

---

## 9. 推荐验证命令

```text
# 两步结束（视模型与 prompt 而定）
tool-call request -p "Describe China in one sentence" -m sensenova

# 跨命令记忆
tool-call request -p "Tell me about China" -m sensenova -c demo
tool-call request -p "What was my first question?" -m sensenova -c demo

# 无记忆对照（不应答出 China）
tool-call request -p "What was my first question?" -m sensenova
```

修改 `core` 后：`mvn -pl core install -DskipTests`，再启动 shell。

---

## 10. Maven：`Unresolved plugin: maven-install-plugin`

### 现象

IDE（Cursor / VS Code Java、IntelliJ）提示无法解析 `org.apache.maven.plugins:maven-install-plugin:3.0.1`，但命令行有时能构建。

### 原因

- 本机访问 **Maven Central**（`repo.maven.apache.org`）较慢或超时；项目已在 `.mvn/settings.xml` 与 `pom.xml` 中配置 **阿里云** 镜像。
- **IDE 默认不读** `.mvn/maven.config`，未使用项目 `settings.xml` 时会直连 Central，插件解析失败。
- `~/.m2/repository` 里若存在失败的 `*.lastUpdated`（尤其 `maven-install-plugin-*.lastUpdated`），会加剧 IDE 报错。

`pom.xml` 里 `maven-install-plugin.version=3.0.1` 是为兼容旧 Maven 的显式版本，与 Central 上 artifact 一致，不是版本号写错。

### 处理步骤

1. **全局 Maven 设置**（推荐，一次即可）：

   ```bash
   mkdir -p ~/.m2
   cp .mvn/settings.xml ~/.m2/settings.xml
   ```

2. **清理失败缓存**（可选）：

   ```bash
   rm -f ~/.m2/repository/org/apache/maven/plugins/maven-install-plugin/maven-install-plugin-*.lastUpdated
   ```

3. **Cursor / VS Code**：工作区已提供 `.vscode/settings.json`，将 `java.configuration.maven.userSettings` 指向 `.mvn/settings.xml`。执行 **Java: Clean Java Language Server Workspace** 或 **Maven: Update Project** 后重载窗口。

4. **IntelliJ**：*Settings → Build → Build Tools → Maven → User settings file* 设为项目内 `.mvn/settings.xml`，再 *Reload All Maven Projects*。

5. **验证**：

   ```bash
   mvn -s .mvn/settings.xml validate
   mvn -s .mvn/settings.xml -pl core install -DskipTests
   ```

---

## 11. Maven：找不到 `com.wish:Janus` / `com.wish:core`（去阿里云下载失败）

### 现象

单独构建 `shell` 时出现类似错误：

```text
Could not find artifact com.wish:Janus:pom:0.0.1-SNAPSHOT in aliyun-public
Could not find artifact com.wish:core:jar:0.0.1-SNAPSHOT in aliyun-public
```

### 原因

`Janus`、`core`、`shell` 是**本仓库内的多模块**，不会发布到阿里云。Maven 只有在解析 `shell` 对 `core` 的依赖、或读取 `core` 的 POM 时，才会去远程找这些坐标；若尚未在本地 `install`，就会报上述错误。

常见触发方式：只在 `shell/` 目录执行 `mvn package`，或 IDE 只编译 `shell` 模块且未先安装 `core`。

### 正确做法

在**项目根目录**执行（推荐）：

```bash
cd /path/to/Janus
mvn -s .mvn/settings.xml install -DskipTests
```

若只想打 `shell`，也要带上依赖模块：

```bash
mvn -s .mvn/settings.xml -pl shell -am install -DskipTests
```

修改 `core` 后只需重装 core（FAQ §9）：

```bash
mvn -s .mvn/settings.xml -pl core install -DskipTests
```

若曾因失败产生远程拉取标记，可删除后重装：

```bash
rm -rf ~/.m2/repository/com/wish/Janus/0.0.1-SNAPSHOT/*.lastUpdated
```

---

## 12. 其它已知改进方向（未全部落地）

- 缩短或改写 `NEXT_STEP_PROMPT`，标明 **非用户消息**，减少 Step 2～3 自指循环。
- `terminate` 结果不展示在 Shell `Step N`（仅内部结束）。
- 纯文本且无 tool 的 step 在「已 `create_chat_completion`」后跳过展示。
- 将会话持久化到磁盘（当前仅 JVM 内缓存）。
