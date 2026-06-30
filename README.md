# FM-Agent-DevEco-Plugin

DevEco Studio IDE plugin for installing and running FM-Agent inside the IDE, with direct access to verification logs and result summaries.

Chinese version: [README_zh.md](README_zh.md)

## Overview

`FM-Agent-DevEco-Plugin` is a DevEco Studio IDE plugin for running the FM-Agent verification workflow directly from the current DevEco/HarmonyOS project. It provides an `FM Agent` Tool Window for FM-Agent installation, environment checks, project verification, runtime log monitoring, and result summary viewing.

The plugin is currently organized as an IntelliJ Platform plugin. It is compiled and packaged against local DevEco Studio platform classes and does not depend on Gradle.

## Features

- Opens the `FM Agent` Tool Window from `Tools > FM Agent: Open Panel` in DevEco Studio.
- Installs FM-Agent.
  - Configures the local `FM-Agent` path from the plugin panel.
  - When the configured path does not exist, clones FM-Agent from `https://github.com/fmagent-project/FM-Agent.git` and runs `./install.sh`.
  - Ensures that the current DevEco project has a Git repository and an initial commit before verification.
- Verifies code in the current project.
  - Supports FM-Agent's `Resume` and `Isolate` verification options.
  - Runs environment checks for FM-Agent, OpenCode, the OpenAI-compatible API, and an OpenCode smoke test.
  - Displays process output and runtime diagnostics in real time in the `Monitor` tab, including:
    - `fm_agent/fm_agent.log`
    - `fm_agent/trace/events.jsonl`
    - `fm_agent/trace/payloads/*_opencode.log`
- Displays bug defects for the current project.
  - Summarizes logic verification and bug validation results in the `Verify Result` tab.
  - Shows mismatch functions and confirmed bugs, and supports opening result files directly in the IDE by clicking them.

## Requirements

To build the plugin:

- DevEco Studio for macOS
- JDK 21 or later

To run FM-Agent verification, the machine must already have the following installed, or be able to install them through `Install FM-Agent`:

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
5. Select `build/distributions/fm-agent-deveco-plugin-0.5.0.zip`.
6. Restart DevEco Studio.

## Usage

1. Open the DevEco/HarmonyOS project you want to verify.
2. Open the plugin through `Tools > FM Agent: Open Panel`, or open the `FM Agent` Tool Window on the right.
3. Enter the local FM-Agent directory in `FM-Agent path`.
4. Click `Install FM-Agent`. If the directory does not exist, the plugin first clones FM-Agent and then runs the installation script.
5. Click `Check Environment` to confirm that local tools, FM-Agent configuration, API access, OpenCode configuration, and the OpenCode smoke test are available.
6. Click `Verify Project` to start verifying the current project.
7. View real-time output in the `Monitor` tab, and view the result summary in the `Verify Result` tab.
8. Click `Get Results` to manually refresh the latest verification results for the target project.

## Result Files

The plugin reads results from the `fm_agent` directory under the current DevEco project:

```text
<project>/fm_agent/
  fm_agent.log
  trace/events.jsonl
  trace/payloads/*_opencode.log
  extracted_functions/
  logic_verification_results/
  bug_validation/summary.json
```

`Verify Result` reports:

- number of extracted functions
- number of verified functions
- `MATCH` / `MISMATCH` / unverified counts
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
build/distributions/fm-agent-deveco-plugin-0.5.0.zip
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

Run `Check Environment` first. If the smoke test or verification workflow hangs, check the real-time output in the `Monitor` tab:

```text
fm_agent/trace/payloads/*_opencode.log
~/.local/share/opencode/log/opencode.log
```

For debugging, keep `OpenCode timeout(s)` at `300`. For full project verification, increase it to `1200` or `1800`.

### The project has no Git commit before verification

Before verification, the plugin checks whether the current project has a usable `HEAD`. If not, it automatically runs `git init`, `git add -A`, and a local initialization commit so that FM-Agent can run against a Git-backed project state.

## Repository Layout

```text
src/main/java/com/fmagent/deveco/   plugin Java source code
src/main/resources/META-INF/        IntelliJ/DevEco plugin descriptor
scripts/build-plugin.sh             local no-Gradle build script
scripts/open-example-project.sh      opens the example project
scripts/repro-*.py                   debugging and reproduction scripts
docs/                               development research notes
```
