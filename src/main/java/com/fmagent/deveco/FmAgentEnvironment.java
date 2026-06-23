package com.fmagent.deveco;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class FmAgentEnvironment {
    private FmAgentEnvironment() {
    }

    static String describe(Path fmAgentHome, String timeoutSeconds) {
        String lineSeparator = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append("Environment summary:").append(lineSeparator);
        builder.append("- FM-Agent home: ").append(fmAgentHome).append(lineSeparator);
        builder.append("- OpenCode timeout(s): ").append(timeoutSeconds == null || timeoutSeconds.isBlank() ? "<FM-Agent default>" : timeoutSeconds).append(lineSeparator);

        Path envPath = fmAgentHome.resolve(".env");
        if (!Files.isRegularFile(envPath)) {
            builder.append("- .env: missing").append(lineSeparator);
            return builder.toString();
        }

        builder.append("- .env: ").append(envPath).append(lineSeparator);
        try {
            List<String> lines = Files.readAllLines(envPath, StandardCharsets.UTF_8);
            appendValue(builder, lines, "OPENCODE_MODEL_PROVIDER");
            appendValue(builder, lines, "LLM_MODEL");
            appendValue(builder, lines, "MODEL_ID");
            appendValue(builder, lines, "LLM_API_BASE_URL");
            appendValue(builder, lines, "BASE_URL");
        } catch (IOException exception) {
            builder.append("- could not read .env: ").append(exception.getMessage()).append(lineSeparator);
        }
        return builder.toString();
    }

    private static void appendValue(StringBuilder builder, List<String> lines, String key) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + "=")) {
                builder.append("- ").append(key).append(": ").append(stripQuotes(trimmed.substring(key.length() + 1))).append(System.lineSeparator());
                return;
            }
        }
    }

    private static String stripQuotes(String value) {
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
