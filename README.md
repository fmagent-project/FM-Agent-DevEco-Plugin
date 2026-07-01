# FM-Agent-DevEco-Plugin

DevEco Studio IDE plugin for installing and running FM-Agent inside the IDE, with direct access to reasoning logs and result summaries.

Chinese version: [README_zh.md](README_zh.md)

## Overview

`FM-Agent-DevEco-Plugin` is a DevEco Studio IDE plugin for running the FM-Agent reasoning workflow directly from the current DevEco/HarmonyOS project. It provides an `FM Agent` Tool Window for FM-Agent installation, environment checks, project reasoning, runtime log monitoring, and result summary viewing.

The plugin is currently organized as an IntelliJ Platform plugin. It is compiled and packaged against local DevEco Studio platform classes and does not depend on Gradle.

## Features

- Opens the `FM Agent` Tool Window from `Tools > FM Agent: Open Panel` in DevEco Studio.
- Installs FM-Agent.
  - Configures the local `FM-Agent` path from the plugin panel.
  - When the configured path does not exist, clones FM-Agent from `https://github.com/fmagent-project/FM-Agent.git` and runs `./install.sh`.
  - Writes FM-Agent `.env` settings through `Configure Environment`, including API key, base URL, model, and OpenCode provider.
  - Ensures that the current DevEco project has a Git repository and an initial commit before reasoning.
- Reasons about code in the current project.
  - Supports FM-Agent's `Resume`, `Isolate`, and `Incremental` reasoning options.
  - Creates an intent file for incremental reasoning from the text entered in the plugin.
  - Saves pending project changes to a local Git commit before incremental reasoning, excluding `fm_agent/`.
  - Runs environment checks for FM-Agent, OpenCode, the OpenAI-compatible API, and an OpenCode smoke test.
  - Displays process output and runtime diagnostics in real time in the `Monitor` tab, including:
    - `fm_agent/fm_agent.log`
    - `fm_agent/trace/events.jsonl`
    - `fm_agent/trace/payloads/*_opencode.log`
- Displays bug defects for the current project.
  - Summarizes logic reasoning and bug validation results in the `Reasoning Result` tab.
  - Shows mismatch functions and confirmed bugs, and supports opening result files directly in the IDE by clicking them.

## Requirements

To build the plugin:

- DevEco Studio for macOS
- JDK 21 or later

To run FM-Agent reasoning, the machine must already have the following installed, or be able to install them through `Install FM-Agent`:

- `git`
- `uv`
- `python3`
- `opencode`
- Required FM-Agent `.env` configuration, such as `LLM_API_KEY`, `LLM_API_BASE_URL`, `LLM_MODEL`, and `OPENCODE_MODEL_PROVIDER`
- Matching provider/model configuration in `~/.config/opencode/opencode.json`

Before the first run, it is recommended to click `Check Environment`.

## Install into DevEco Studio

1. Open DevEco Studio.
2. Go to `Settings/Preferences > Plugins`.
3. Click the gear icon on the Plugins page.
4. Choose `Install Plugin from Disk`.
5. Select `build/distributions/fm-agent-deveco-plugin-0.6.0.zip`.
6. Restart DevEco Studio.

## Usage

1. Open the DevEco/HarmonyOS project you want FM-Agent to reason about.
2. Open the plugin through `Tools > FM Agent: Open Panel`, or open the `FM Agent` Tool Window on the right.
3. Enter the local FM-Agent directory in `FM-Agent path`.
4. Click `Install FM-Agent`. If the directory does not exist, the plugin first clones FM-Agent and then runs the installation script.
5. Click `Configure Environment` to write FM-Agent `.env` values: `LLM_API_KEY`, `LLM_API_BASE_URL`, `LLM_MODEL`, and `OPENCODE_MODEL_PROVIDER`.
6. Click `Check Environment` to confirm that local tools, FM-Agent configuration, API access, OpenCode configuration, and the OpenCode smoke test are available.
7. Optional: enable `Incremental` and enter the intent text when prompted. The plugin creates the intent file automatically.
8. Click `Reason About Project` to start reasoning about the current project.
9. View real-time output in the `Monitor` tab, and view the result summary in the `Reasoning Result` tab.
10. Click `Get Results` to manually refresh the latest reasoning results for the target project.

When `Incremental` is enabled, the plugin runs FM-Agent with:

```bash
uv run python -u main.py <project> [--isolate] --incremental <intent-file>
```

`Resume` is not passed in incremental mode. Before launching FM-Agent, the plugin makes sure the target project has a Git `HEAD` and commits pending project changes, excluding the `fm_agent/` output directory. This gives FM-Agent a stable current commit to record in `fm_agent/version.log`. If `fm_agent/version.log` is missing, FM-Agent falls back to a full run.

## Result Files

The plugin reads results from the `fm_agent` directory under the current DevEco project:

```text
<project>/fm_agent/
  fm_agent.log
  trace/events.jsonl
  trace/payloads/*_opencode.log
  extracted_functions/
  <logic reasoning result files>/
  incremental_intents/
  bug_validation/summary.json
```

Incremental intent files created by the plugin are written to `<project>/fm_agent/incremental_intents/`.

`Reasoning Result` reports:

- number of extracted functions
- number of functions reasoned about
- `MATCH` / `MISMATCH` / no reasoning result counts
- reported / confirmed / not confirmed / error bug counts
- first 20 mismatch result files
- report files for confirmed bugs

## Build

Build the plugin with the script included in this repository:

```bash
./scripts/build-plugin.sh
```

If DevEco Studio is not installed in the default location, set `DEVECO_HOME`:

```bash
DEVECO_HOME=/Applications/DevEco-Studio.app/Contents ./scripts/build-plugin.sh
```

If you need to specify the JDK:

```bash
JAVAC=/path/to/jdk-21/bin/javac JAR=/path/to/jdk-21/bin/jar ./scripts/build-plugin.sh
```

Build artifact:

```bash
build/distributions/fm-agent-deveco-plugin-0.6.0.zip
```

## Troubleshooting

### DevEco path not found during build

Check the DevEco Studio installation path and set it explicitly:

```bash
DEVECO_HOME=/path/to/DevEco-Studio.app/Contents ./scripts/build-plugin.sh
```

### JDK version does not meet the requirement during build

DevEco Studio 6.1 platform classes use Java 21 bytecode, so JDK 21+ is required. You can specify it with `JAVAC` and `JAR`:

```bash
JAVAC=/path/to/jdk-21/bin/javac JAR=/path/to/jdk-21/bin/jar ./scripts/build-plugin.sh
```

### OpenCode produces no output for a long time

Run `Check Environment` first. If the smoke test or reasoning workflow hangs, check the real-time output in the `Monitor` tab:

```text
fm_agent/trace/payloads/*_opencode.log
~/.local/share/opencode/log/opencode.log
```

For debugging, keep `OpenCode timeout(s)` at `300`. For full project reasoning, increase it to `1200` or `1800`.

### The project has no Git commit before reasoning

Before reasoning, the plugin checks whether the current project has a usable `HEAD`. If not, it automatically runs `git init`, stages project files while ignoring `fm_agent/`, and creates a local initialization commit so that FM-Agent can run against a Git-backed project state.

## Repository Layout

```text
src/main/java/com/fmagent/deveco/   plugin Java source code
src/main/resources/META-INF/        IntelliJ/DevEco plugin descriptor
scripts/build-plugin.sh             local no-Gradle build script
scripts/open-example-project.sh      opens the example project
scripts/repro-*.py                   debugging and reproduction scripts
docs/                               development research notes
```
