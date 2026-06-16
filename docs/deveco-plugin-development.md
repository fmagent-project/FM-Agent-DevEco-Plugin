# DevEco Studio 插件开发调研记录

调研日期：2026-06-16

## 结论

“DevEco 插件开发”需要先区分两类插件：

1. DevEco Studio IDE 插件：扩展 IDE 菜单、工具栏、Tool Window、编辑器行为、代码分析等能力。DevEco Studio 是鸿蒙官方 IDE，并支持从插件页安装插件；这类插件应优先按 IntelliJ Platform Plugin 的方式开发和打包。
2. Hvigor 构建插件/任务：扩展 HarmonyOS 工程构建流程，例如动态改包名、生成代码、注册构建任务、读取 app/module 上下文。这类插件应使用 `@ohos/hvigor` 和 `@ohos/hvigor-ohos-plugin`。

两者不是同一种插件。IDE 插件运行在 DevEco Studio 进程里；Hvigor 插件运行在工程构建流程里。

## 资料来源

- 华为 DevEco Studio 首页：DevEco Studio 是鸿蒙应用与元服务开发 IDE，提供编译构建、预览、调试、性能调优等能力。<https://developer.huawei.com/consumer/cn/deveco-studio/>
- DevEco Marketplace：鸿蒙生态开发资源聚合入口。<https://repo.harmonyos.com/>
- 华为 ASCF 插件安装说明：DevEco Studio 的 Plugins 页面支持通过齿轮菜单选择 `Install Plugin from Disk` 手动安装插件。<https://developer.huawei.com/consumer/en/doc/atomic-ascf/ascf-plugin>
- 华为 Hvigor 扩展构建入口：官方文档入口包含“开发 Hvigor 任务”“开发 Hvigor 插件”“扩展构建 API”。<https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-build-expanding>
- 华为 Hvigor 任务文档：使用 `HvigorNode` 注册任务，并通过 `hvigorw customTask` 执行。<https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-hvigor-task>
- 华为 Hvigor 插件上下文：`OhosAppContext` 等上下文从 `@ohos/hvigor-ohos-plugin` 导入。<https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-build-expanding-context>
- JetBrains IntelliJ Platform SDK：IntelliJ 平台插件开发官方文档。<https://plugins.jetbrains.com/docs/intellij/welcome.html>
- JetBrains `plugin.xml` 配置说明：`plugin.xml` 声明插件元数据、扩展、Actions、Listeners 等。<https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html>
- JetBrains IntelliJ Platform Gradle Plugin 2.x：用于构建、测试、验证、发布 IntelliJ-based IDE 插件。<https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html>
- 参考文章：HarmonyOS5 DevEco Studio 插件开发指南。该文可作为思路参考，但部分写法未在官方文档中核实。<https://harmonyosdev.csdn.net/6915731c5511483559e9f245.html>

## 路线一：开发 DevEco Studio IDE 插件

适用场景：

- 在菜单、工具栏或右键菜单增加命令。
- 增加 Tool Window、设置页、通知、项目视图扩展。
- 操作编辑器、文件、PSI、项目模型。
- 做 ArkTS/ArkUI 辅助编码、模板生成、工程检查、AI 面板等 IDE 侧能力。

推荐按 IntelliJ Platform 插件工程组织，因为 DevEco Studio 的插件安装和大量 IDE 扩展模型与 IntelliJ 系插件一致。

### 建议脚手架

使用 Gradle + Kotlin/Java。JetBrains 目前推荐 `org.jetbrains.intellij.platform` 2.x 插件；具体 Gradle 插件版本、Gradle 版本和 Java 版本要求应以 JetBrains 官方页面为准。若目标 DevEco Studio 版本较旧，可能需要回退到旧版 Gradle IntelliJ Plugin 1.x。

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

rootProject.name = "fm-agent-deveco-plugin"
```

`build.gradle.kts`：

```kotlin
plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "<check-latest-compatible-version>"
}

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    intellijPlatform {
        // 示例：先用与目标 DevEco Studio 基线接近的 IntelliJ IDEA Community 版本开发。
        intellijIdeaCommunity("2024.3")

        // 如果 Gradle 插件能识别本机 DevEco Studio，也可以改为本地 IDE：
        // local("/Applications/DevEco-Studio.app")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.example.fmagent.deveco"
        name = "FM Agent DevEco Plugin"
        version = project.version.toString()
        vendor {
            name = "FM Agent"
        }
        ideaVersion {
            // 示例值，对应 IntelliJ Platform 2024.3。实际值应按目标 DevEco Build Version 调整。
            sinceBuild = "243"
        }
    }
}
```

注意：本地 DevEco Studio 的安装路径、产品布局和 build number 需要在机器上实际验证。若 `local("/path/to/DevEco Studio")` 无法解析，可以先用匹配基线的 IntelliJ IDEA Community 开发，再将 `buildPlugin` 产物安装到 DevEco Studio 验证。

### `plugin.xml` 基本结构

`src/main/resources/META-INF/plugin.xml`：

```xml
<idea-plugin>
    <id>com.example.fmagent.deveco</id>
    <name>FM Agent DevEco Plugin</name>
    <vendor>FM Agent</vendor>

    <depends>com.intellij.modules.platform</depends>

    <actions>
        <action
            id="com.example.fmagent.deveco.OpenPanelAction"
            class="com.example.fmagent.deveco.OpenPanelAction"
            text="Open FM Agent"
            description="Open FM Agent tool window">
            <add-to-group group-id="ToolsMenu" anchor="last"/>
        </action>
    </actions>

    <extensions defaultExtensionNs="com.intellij">
        <toolWindow
            id="FM Agent"
            anchor="right"
            factoryClass="com.example.fmagent.deveco.FmAgentToolWindowFactory"/>
    </extensions>
</idea-plugin>
```

JetBrains 官方文档中，Actions 应放在 `<actions>` 节点下，而不是放在 `<extensions>` 节点下。用户给出的 CSDN 示例把 action 写在 `<extensions defaultExtensionNs="com.huawei.deveco">` 内，这一点需要在目标 DevEco Studio 版本里实际验证后再采用。

### Action 示例

```java
package com.example.fmagent.deveco;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public class OpenPanelAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Messages.showInfoMessage(project, "FM Agent action invoked.", "FM Agent");
    }
}
```

### 构建、安装、调试

常用命令：

```bash
./gradlew runIde
./gradlew buildPlugin
```

调试流程：

1. 先用 `runIde` 在沙箱 IDE 中验证插件基本逻辑。
2. 使用 `buildPlugin` 生成 ZIP。
3. 在 DevEco Studio 中打开 `Settings/Preferences > Plugins`，通过齿轮菜单选择 `Install Plugin from Disk`，安装 ZIP 或 JAR。
4. 重启 DevEco Studio，确认菜单、Tool Window、日志和异常行为。

兼容性检查：

- 记录目标 DevEco Studio 的 `Help > About` 中 Build Version、Runtime version、JBR/JDK 版本。
- 将 `sinceBuild` 设置到目标 DevEco 对应的 IntelliJ Platform build 基线。
- 尽量避免直接调用未公开的 DevEco 内部类。必须调用时，把依赖声明、反射兜底和版本检查写清楚。
- 对 ArkTS/ArkUI 文件做处理时，先确认 DevEco 内置语言插件暴露的 PSI、FileType、Language ID；不要直接假设存在 `ArkTSLanguage.INSTANCE` 或 `ArkTSParserUtil`。

## 路线二：开发 Hvigor 构建插件/任务

适用场景：

- 构建前后生成文件、改配置、收集产物。
- 根据命令行参数或 build mode 动态调整 app/module 配置。
- 统一团队构建规则，复用到多个 HarmonyOS 工程。

### 在 `hvigorfile.ts` 中快速开发

工程级或模块级 `hvigorfile.ts` 可直接声明插件：

```ts
import { HvigorNode, HvigorPlugin } from '@ohos/hvigor';
import { appTasks } from '@ohos/hvigor-ohos-plugin';

function customPlugin(): HvigorPlugin {
  return {
    pluginId: 'customPlugin',
    apply(node: HvigorNode) {
      console.log(`node: ${node.getNodeName()}, path: ${node.getNodePath()}`);
      console.log('hello customPlugin!');
    }
  };
}

export default {
  system: appTasks,
  plugins: [
    customPlugin()
  ]
};
```

这种方式适合验证想法，但多个工程复用时会导致 `hvigorfile.ts` 变重。

### 注册自定义任务

```ts
import { getNode, HvigorNode } from '@ohos/hvigor';

const node: HvigorNode = getNode(__filename);

node.registerTask({
  name: 'customTask',
  run() {
    console.log('this is Task');
  }
});
```

执行：

```bash
./hvigorw customTask
```

### 读取 HarmonyOS 工程上下文

需要读 app/module 信息时，通过 `@ohos/hvigor-ohos-plugin` 获取上下文：

```ts
import { HvigorNode, HvigorPlugin } from '@ohos/hvigor';
import { OhosAppContext, OhosPluginId, appTasks } from '@ohos/hvigor-ohos-plugin';

function inspectAppPlugin(): HvigorPlugin {
  return {
    pluginId: 'inspectAppPlugin',
    apply(node: HvigorNode) {
      const appContext = node.getContext(OhosPluginId.OHOS_APP_PLUGIN) as OhosAppContext;
      console.log(appContext);
    }
  };
}

export default {
  system: appTasks,
  plugins: [
    inspectAppPlugin()
  ]
};
```

模块级插件通常使用 `hapTasks` 或对应模块任务入口，并通过 `OhosPluginId.OHOS_HAP_PLUGIN` 获取 HAP 上下文。具体类型和方法以当前 DevEco/Hvigor 版本自带声明为准。

### 可复用插件包

当插件需要跨项目复用时，推荐拆成独立 TypeScript 包：

1. 新建包，依赖 `@ohos/hvigor`、必要时依赖 `@ohos/hvigor-ohos-plugin`。
2. 导出 `function xxxPlugin(options): HvigorPlugin`。
3. 编译输出 JS 和 `.d.ts`。
4. 发布到团队私有 npm/ohpm 仓库，或用 workspace/file dependency 引入。
5. 在业务工程 `hvigorfile.ts` 中导入并加入 `plugins: [xxxPlugin()]`。

## 对用户给定 CSDN 文章的判断

该文章提供了有价值的方向：JDK 17、`plugin.xml`、`AnAction`、`runIde`、`buildPlugin`、后台任务、版本兼容等都符合 IntelliJ 系插件开发常识。

但以下内容需要谨慎：

- `gradle init --type deveco-plugin`：未在官方 Gradle、JetBrains、Huawei 文档中找到该 init type，不能直接作为推荐命令。
- `<extensions defaultExtensionNs="com.huawei.deveco">` 内声明 `<action>`：JetBrains 官方文档将 action 放在 `<actions>` 下。除非 DevEco 有额外私有 DSL，否则应先按 `<actions>` 实现。
- `ArkTSLanguage.INSTANCE`、`ArkTSParserUtil`、`TemplateManager`：这些看起来像 DevEco 私有 API 或示例占位，使用前必须在目标 DevEco SDK/插件依赖中确认类是否存在。
- “发布到 Huawei DevEco Marketplace”的具体提交流程：公开资料更多证明了 DevEco Studio 支持插件市场和磁盘安装，但第三方 IDE 插件上架流程需要进一步拿到华为官方入口或账号后台文档确认。

## 推荐落地顺序

1. 明确目标是 IDE 插件还是构建插件。如果是 FM Agent 嵌入 DevEco Studio，一般选 IDE 插件；如果是构建自动化，选 Hvigor 插件。
2. 记录本机 DevEco Studio 版本：`Help > About` 的版本号、Build Version、Runtime version。
3. 先做最小插件：
   - IDE 插件：一个 `ToolsMenu` action + 一个 Tool Window。
   - Hvigor 插件：一个 `customPlugin()` + 一个 `customTask`。
4. 在 DevEco Studio 中验证安装或构建执行。
5. 再接入 ArkTS/ArkUI、工程结构、AI 服务、代码生成等业务能力。
