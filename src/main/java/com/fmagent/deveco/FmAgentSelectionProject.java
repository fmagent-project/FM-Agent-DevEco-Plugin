package com.fmagent.deveco;

import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class FmAgentSelectionProject {
    private FmAgentSelectionProject() {
    }

    static Path create(String selectedText, VirtualFile sourceFile) throws IOException {
        Path root = Files.createTempDirectory("fm-agent-selection-");
        String extension = extensionOf(sourceFile);
        Path sourceDir = root.resolve("src");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("selected." + extension), selectedText + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("README.md"), "Temporary project generated from a DevEco editor selection." + System.lineSeparator(), StandardCharsets.UTF_8);
        initGitRepo(root);
        return root;
    }

    private static String extensionOf(VirtualFile file) {
        if (file == null || file.getExtension() == null || file.getExtension().isBlank()) {
            return "cpp";
        }

        String extension = file.getExtension().toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "ets", "ts", "js", "c", "cc", "cpp", "cxx", "h", "hpp", "hh", "py", "java", "go", "rs" -> extension;
            default -> "cpp";
        };
    }

    private static void initGitRepo(Path root) throws IOException {
        run(root, "git", "init");
        run(root, "git", "add", ".");
        run(root, "git", "-c", "user.name=FM Agent", "-c", "user.email=fm-agent@example.invalid",
                "commit", "-m", "Add selected code");
    }

    private static void run(Path directory, String... command) throws IOException {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException(String.join(" ", command) + " failed with code " + exitCode + ": " + output);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while preparing selection project.", exception);
        }
    }
}
