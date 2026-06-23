# FM-Agent-DevEco-Plugin

DevEco Studio plugin demo for running FM-Agent from inside the IDE.

## What it does

- Adds `Tools > FM Agent: Open Panel` to open the `FM Agent` Tool Window.
- Configures the local `FM-Agent-Internal` path from the IDE.
- Clones FM-Agent from `https://github.com/fmagent-project/FM-Agent.git` when the configured local path does not exist, then runs `./install.sh` from that path.
- Initializes a Git repository in the current DevEco project after install when the project has no `.git` entry.
- Runs `uv run python main.py <project>` against the current DevEco project.
- Runs an environment self-check for FM-Agent, OpenCode, the configured OpenAI-compatible API, and an OpenCode smoke test.
- Displays process output, `summary.json` counts, bug report paths, logic verification counts, and the tail of `fm_agent/fm_agent.log`.
- Streams FM-Agent runtime diagnostics while a run is still active, including `fm_agent/fm_agent.log`, `fm_agent/trace/events.jsonl`, and `fm_agent/trace/payloads/*_opencode.log`.
- Shows a redacted environment summary for provider/model/base URL and lets you set `OPENCODE_TIMEOUT_SECONDS` from the panel.
- Enables verbose OpenCode child-process logs for verification runs with `OPENCODE_PRINT_LOGS=1` and `OPENCODE_LOG_LEVEL=INFO`.

## Build

This repo includes a no-Gradle build script that compiles against your local DevEco Studio installation:

```bash
./scripts/build-plugin.sh
```

DevEco Studio 6.1 platform classes use Java 21 bytecode, so the build script requires JDK 21+. If `JAVAC` is not set, it first tries to use a JDK under `build/tools`, then falls back to `javac` on `PATH`.

Default DevEco path:

```bash
/Applications/DevEco-Studio.app/Contents
```

Override it when needed:

```bash
DEVECO_HOME=/Applications/DevEco-Studio.app/Contents ./scripts/build-plugin.sh
```

The plugin ZIP is generated at:

```bash
build/distributions/fm-agent-deveco-plugin-0.5.0.zip
```

## Import into DevEco Studio

1. Open DevEco Studio.
2. Go to `Settings/Preferences > Plugins`.
3. Click the gear icon.
4. Choose `Install Plugin from Disk`.
5. Select `build/distributions/fm-agent-deveco-plugin-0.5.0.zip`.
6. Restart DevEco Studio.

## Test with ExampleCppApp

Open the test project:

```bash
./scripts/open-example-project.sh
```

Or open it manually:

```bash
/Users/lianganran/codes/2_SJTU_code/FM-agent/ExampleCppApp
```

After the project opens, use `Tools > FM Agent: Open Panel` or open the `FM Agent` Tool Window.

Default FM-Agent path in the Tool Window:

```bash
/Users/lianganran/codes/2_SJTU_code/FM-agent/FM-Agent-Internal
```

Use `Check Environment` before the first run or when OpenCode appears idle. It checks local tools, imports FM-Agent config, calls the configured `/chat/completions` endpoint, and runs `opencode --print-logs --log-level INFO run ... 'reply with OK only'`.

Use `Verify Project` for the whole DevEco project.

If OpenCode hangs without producing output, first run `Check Environment` and check the streamed `fm_agent/trace/payloads/*_opencode.log` output. The default timeout is `300` seconds for faster debugging; use `1200` or `1800` for full project runs.
