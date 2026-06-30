# FM-Agent-DevEco-Plugin

DevEco Studio IDE 插件，用于在 IDE 内安装并运行 FM-Agent，并直接查看验证日志和结果汇总。

English version: [README.md](README.md)

## 项目简介

`FM-Agent-DevEco-Plugin` 是一个 DevEco Studio IDE 插件，用于在当前 DevEco/HarmonyOS 工程中直接运行 FM-Agent 验证流程。插件提供 `FM Agent` Tool Window，可完成 FM-Agent 安装、环境检查、项目验证、运行日志监控和结果汇总查看。

插件目前按 IntelliJ Platform 插件方式组织，通过本地 DevEco Studio 平台类编译打包，不依赖 Gradle。

## 功能

- 在 DevEco Studio 的 `Tools > FM Agent: Open Panel` 中打开 `FM Agent` Tool Window。
- 安装FM-agent
  - 在插件面板中配置本地 `FM-Agent` 路径。
  - 当配置路径不存在时，从 `https://github.com/fmagent-project/FM-Agent.git` 克隆 FM-Agent，并执行 `./install.sh`。
  - 在验证前确保当前 DevEco 工程具备 Git 仓库和初始提交。
- 对当前项目进行代码验证
  - 支持FM-agent的 `Resume` 和 `Isolate` 验证选项。
  - 执行 FM-Agent、OpenCode、OpenAI-compatible API 和 OpenCode smoke test 环境自检。
  - 在 `Monitor` 页实时显示进程输出和运行诊断，包括：
    - `fm_agent/fm_agent.log`
    - `fm_agent/trace/events.jsonl`
    - `fm_agent/trace/payloads/*_opencode.log`
- 展示当前项目bug缺陷
  - 在 `Verify Result` 页汇总逻辑验证和 Bug 验证结果。
  - 显示 mismatch 函数、confirmed bugs，并支持点击结果文件直接在 IDE 中打开。

## 环境要求

构建插件需要：

- macOS版 DevEco Studio
- JDK 21 或更高版本

运行 FM-Agent 验证需要当前机器已经具备或能通过 `Install FM-Agent` 安装：

- `git`
- `uv`
- `python3`
- `opencode`
- 进行FM-Agent `.env` 中的必要配置，例如 `LLM_API_KEY`、`LLM_API_BASE_URL`、`LLM_MODEL`、`OPENCODE_MODEL_PROVIDER`
- `~/.config/opencode/opencode.json` 中对应的 provider/model 配置

建议第一次运行前先点击 `Check Environment`。

## 安装到 DevEco Studio

1. 打开 DevEco Studio。
2. 进入 `Settings/Preferences > Plugins`。
3. 点击插件页面的齿轮按钮。
4. 选择 `Install Plugin from Disk`。
5. 选择 `build/distributions/fm-agent-deveco-plugin-0.5.0.zip`。
6. 重启 DevEco Studio。

## 使用流程

1. 打开需要验证的 DevEco/HarmonyOS 工程。
2. 通过 `Tools > FM Agent: Open Panel` 打开插件，或直接打开右侧 `FM Agent` Tool Window。
3. 在 `FM-Agent path` 中填写本地 FM-Agent 目录。
4. 点击 `Install FM-Agent`。如果目录不存在，插件会先克隆 FM-Agent，再执行安装脚本。
5. 点击 `Check Environment`，确认本地工具、FM-Agent 配置、API、OpenCode 配置和 OpenCode smoke test 均可用。
6. 点击 `Verify Project` 开始验证当前工程。
7. 在 `Monitor` 页查看实时输出；在 `Verify Result` 页查看结果汇总。
8.  点击 `Get Results` 可手动刷新最近一次目标工程的验证结果。

## 结果目录

插件从当前 DevEco 工程下的 `fm_agent` 目录读取结果：

```text
<project>/fm_agent/
  fm_agent.log
  trace/events.jsonl
  trace/payloads/*_opencode.log
  extracted_functions/
  logic_verification_results/
  bug_validation/summary.json
```

`Verify Result` 会统计：

- 提取函数数量
- 已验证函数数量
- `MATCH` / `MISMATCH` / 未验证数量
- reported / confirmed / not confirmed / error bug 数量
- 前 20 个 mismatch 结果文件
- confirmed bug 对应的报告文件

## 构建

使用仓库内置脚本构建插件：

```bash
./scripts/build-plugin.sh
```

如果 DevEco Studio 不在默认路径，设置 `DEVECO_HOME`：

```bash
DEVECO_HOME=/Applications/DevEco-Studio.app/Contents ./scripts/build-plugin.sh
```

如果需要指定 JDK：

```bash
JAVAC=/path/to/jdk-21/bin/javac JAR=/path/to/jdk-21/bin/jar ./scripts/build-plugin.sh
```

构建产物：

```bash
build/distributions/fm-agent-deveco-plugin-0.5.0.zip
```

## 常见问题

### 构建时报 DevEco 路径不存在

确认 DevEco Studio 安装路径，并显式设置：

```bash
DEVECO_HOME=/path/to/DevEco-Studio.app/Contents ./scripts/build-plugin.sh
```

### 构建时报 JDK 版本不满足

DevEco Studio 6.1 平台类使用 Java 21 字节码，需要 JDK 21+。可通过 `JAVAC` 和 `JAR` 指定：

```bash
JAVAC=/path/to/jdk-21/bin/javac JAR=/path/to/jdk-21/bin/jar ./scripts/build-plugin.sh
```

### OpenCode 长时间没有输出

先运行 `Check Environment`。如果 smoke test 或验证流程卡住，查看 `Monitor` 页中实时输出的：

```text
fm_agent/trace/payloads/*_opencode.log
~/.local/share/opencode/log/opencode.log
```

调试时可保持 `OpenCode timeout(s)` 为 `300`，完整工程验证时再改为 `1200` 或 `1800`。

### 验证前工程没有 Git 提交

插件会在验证前检查当前工程是否存在可用 `HEAD`。如果没有，会自动执行 `git init`、`git add -A` 和一个本地初始化提交，确保 FM-Agent 能基于 Git 状态运行。

## 仓库结构

```text
src/main/java/com/fmagent/deveco/   插件 Java 源码
src/main/resources/META-INF/        IntelliJ/DevEco 插件声明
scripts/build-plugin.sh             无 Gradle 的本地构建脚本
scripts/open-example-project.sh      打开示例工程
scripts/repro-*.py                   调试和复现脚本
docs/                               开发调研记录
```
