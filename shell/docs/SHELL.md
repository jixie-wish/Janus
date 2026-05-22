# janus-shell 用法

> [English](SHELL.en.md) · Agent 原理见 [core/docs/AGENT-FLOW.md](../../core/docs/AGENT-FLOW.md) · 常见问题见 [docs/FAQ.md](../../docs/FAQ.md)

`shell` 是 Janus 的命令行入口：启动后进入 `shell:>`，通过 `tool-call request` 调用 SenseNova 上的 ToolCall Agent。

---

## 环境

- **JDK 21**
- **Maven 3.6.3+**
- 在 `shell/src/main/resources/application.properties` 中配置 API Key 与模型

---

## 启动

在 **Janus 根目录** 执行。若修改过 `core`，先安装再启动：

```bash
mvn -pl core install -DskipTests
mvn -f shell/pom.xml spring-boot:run
```

### Linux / macOS（bash / zsh）

```bash
cd /path/to/Janus

export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"

mvn -pl core install -DskipTests
mvn -f shell/pom.xml spring-boot:run
```

### Windows（PowerShell）

```powershell
cd C:\path\to\Janus

$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

mvn -pl core install -DskipTests
mvn -f shell/pom.xml spring-boot:run
```

### Windows（CMD）

```cmd
cd C:\path\to\Janus

set JAVA_HOME=C:\path\to\jdk-21
set PATH=%JAVA_HOME%\bin;%PATH%

mvn -pl core install -DskipTests
mvn -f shell/pom.xml spring-boot:run
```

看到 `shell:>` 即表示就绪。

---

## 命令

### tool-call request

```text
tool-call request --prompt "<用户任务>" [--model sensenova] [--conversation-id <id>]
```

| 参数 | 简写 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `--prompt` | `-p` | 是 | — | 发给 agent 的内容 |
| `--model` | `-m` | 否 | `sensenova` | CLI 模型别名（当前对应 Spring 配置的 ChatModel） |
| `--conversation-id` | `-c` | 否 | — | 同一次 shell 进程内复用对话 memory；不传则每次请求独立 |

示例：

```text
shell:> tool-call request -p "你好"
shell:> tool-call request --prompt "用一句话介绍 Janus" -m sensenova
shell:> tool-call request -p "Tell me about China" -c demo
shell:> tool-call request -p "我刚才问了什么" -c demo
```

带 `-c` 时输出首行会回显 `conversation-id: ...`，便于确认会话 id。memory 仅在**当前 JVM / shell 进程**内有效，退出 shell 后丢失。

清除缓存：

```text
shell:> tool-call clear-session -c demo
```

返回为多行文本，形如 `Step 1: ...`、`Step 2: ...`（每步 agent 的输出）。

### 其它 Shell 命令

```text
shell:> help
shell:> help tool-call
shell:> clear
shell:> exit
```

---

## 非交互运行（脚本/CI）

**Linux / macOS**

```bash
mvn -f shell/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="tool-call request --prompt 你好 --spring.shell.interactive.enabled=false"
```

**Windows（PowerShell）**

```powershell
mvn -f shell/pom.xml spring-boot:run `
  "-Dspring-boot.run.arguments=tool-call request --prompt 你好 --spring.shell.interactive.enabled=false"
```

**Windows（CMD）**

```cmd
mvn -f shell/pom.xml spring-boot:run -Dspring-boot.run.arguments="tool-call request --prompt 你好 --spring.shell.interactive.enabled=false"
```

---

## 配置说明

文件：`shell/src/main/resources/application.properties`

| 配置项 | 说明 |
|--------|------|
| `spring.ai.openai.api-key` | SenseNova API Key |
| `spring.ai.openai.base-url` | 一般为 `https://token.sensenova.cn/v1` |
| `spring.ai.openai.chat.model` | 实际模型 ID，如 `sensenova-6.7-flash-lite` |
| `janus.agent.max-steps` | 单轮最多执行步数（默认 30） |
| `spring.shell.interactive.enabled` | `true` 保持交互式 `shell:>` |

注意：Spring AI 2.x 使用 `spring.ai.openai.chat.model`，不要用已废弃的 `chat.options.model`。

本地勿提交真实 Key；可用 `application-local.properties`（若项目已 gitignore）。

---

## 验证 API（可选）

不启动 shell，可先测模型是否通：

```bash
cd model-verify
python3 sensenova-6.7-flash-lite.py
python3 sensenova-6.7-flash-lite.py --prompt "你好"
```

（Windows 上若无 `python3`，用 `python`。）

---

## 常见问题

| 现象 | 处理 |
|------|------|
| `engine is not available temporarily` | 多为 API/模型临时不可用；可用 `model-verify` 对比；确认 `chat.model` 配置正确 |
| 修改 core 后行为未变 | 执行 `mvn -pl core install -DskipTests` 后重启 shell |
| 找不到 `java` / 版本不对 | 使用 JDK 21 并设置 `JAVA_HOME` |
| 多步重复寒暄、不结束 | 模型未调用 `terminate`；见 [core 文档](../../core/docs/AGENT-FLOW.md) |
