# FM-Agent-DevEco-Plugin

DevEco Studio plugin demo for running FM-Agent from inside the IDE.

## What it does

- Adds `Tools > FM Agent: Open Panel` to open the `FM Agent` Tool Window.
- Configures the local `FM-Agent-Internal` path from the IDE.
- Runs `./install_mac.sh` from that path on macOS.
- Runs `uv run python main.py <project>` against the current DevEco project.
- Verifies an editor selection by copying the selected code into a temporary git repository, then running FM-Agent on that repository.
- Displays process output, `summary.json` counts, bug report paths, and the tail of `fm_agent/fm_agent.log`.

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
build/distributions/fm-agent-deveco-plugin-0.2.0.zip
```

## Import into DevEco Studio

1. Open DevEco Studio.
2. Go to `Settings/Preferences > Plugins`.
3. Click the gear icon.
4. Choose `Install Plugin from Disk`.
5. Select `build/distributions/fm-agent-deveco-plugin-0.2.0.zip`.
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

Use `Verify Project` for the whole DevEco project. Select code in the editor and use `Verify Selection` for a smaller temporary project.
