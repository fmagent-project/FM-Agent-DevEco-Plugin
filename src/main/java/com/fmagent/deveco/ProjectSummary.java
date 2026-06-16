package com.fmagent.deveco;

import com.intellij.openapi.project.Project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

final class ProjectSummary {
    private static final List<String> HARMONY_FILES = Arrays.asList(
            "hvigorfile.ts",
            "build-profile.json5",
            "oh-package.json5",
            "entry/hvigorfile.ts",
            "entry/build-profile.json5",
            "entry/oh-package.json5",
            "entry/src/main/module.json5"
    );

    private final String projectName;
    private final String basePath;
    private final List<String> existingHarmonyFiles;

    private ProjectSummary(String projectName, String basePath, List<String> existingHarmonyFiles) {
        this.projectName = projectName;
        this.basePath = basePath;
        this.existingHarmonyFiles = existingHarmonyFiles;
    }

    static ProjectSummary from(Project project) {
        String basePath = project == null ? null : project.getBasePath();
        List<String> existingFiles = HARMONY_FILES.stream()
                .filter(relativePath -> basePath != null && Files.exists(Path.of(basePath, relativePath)))
                .toList();

        return new ProjectSummary(
                project == null ? "<no project>" : project.getName(),
                basePath == null ? "<no base path>" : basePath,
                existingFiles
        );
    }

    String asText() {
        String lineSeparator = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append("FM Agent DevEco Minimal Plugin").append(lineSeparator);
        builder.append(lineSeparator);
        builder.append("Project: ").append(projectName).append(lineSeparator);
        builder.append("Base path: ").append(basePath).append(lineSeparator);
        builder.append(lineSeparator);
        builder.append("HarmonyOS project files detected:").append(lineSeparator);

        if (existingHarmonyFiles.isEmpty()) {
            builder.append("- none").append(lineSeparator);
        } else {
            existingHarmonyFiles.forEach(file -> builder.append("- ").append(file).append(lineSeparator));
        }

        return builder.toString();
    }
}
