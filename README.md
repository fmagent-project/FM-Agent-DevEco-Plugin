# FM-Agent-DevEco-Plugin

Minimal DevEco Studio plugin demo.

## What it does

- Adds `Tools > FM Agent: Show Project Info`.
- Adds a right-side `FM Agent` Tool Window.
- Reads the currently opened project path and detects common HarmonyOS files such as `hvigorfile.ts`, `build-profile.json5`, and `oh-package.json5`.

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
build/distributions/fm-agent-deveco-plugin-0.1.0.zip
```

## Import into DevEco Studio

1. Open DevEco Studio.
2. Go to `Settings/Preferences > Plugins`.
3. Click the gear icon.
4. Choose `Install Plugin from Disk`.
5. Select `build/distributions/fm-agent-deveco-plugin-0.1.0.zip`.
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

After the project opens, use `Tools > FM Agent: Show Project Info` or open the `FM Agent` Tool Window.
