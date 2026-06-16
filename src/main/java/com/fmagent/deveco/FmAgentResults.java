package com.fmagent.deveco;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FmAgentResults {
    private static final Pattern COUNT_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*(\\d+)");
    private static final Pattern BUG_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BUG_STATUS_PATTERN = Pattern.compile("\"confirmation_status\"\\s*:\\s*\"([^\"]+)\"");

    private FmAgentResults() {
    }

    static String render(Path targetPath) {
        String lineSeparator = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        Path workDir = targetPath.resolve("fm_agent");
        Path summaryPath = workDir.resolve("bug_validation").resolve("summary.json");
        Path logPath = workDir.resolve("fm_agent.log");

        builder.append(lineSeparator);
        builder.append("=== Verification Result ===").append(lineSeparator);
        builder.append("Target: ").append(targetPath).append(lineSeparator);

        if (Files.isRegularFile(summaryPath)) {
            try {
                String summary = Files.readString(summaryPath, StandardCharsets.UTF_8);
                builder.append("Summary: ").append(summaryPath).append(lineSeparator);
                builder.append("Reported: ").append(count(summary, "total_reported")).append(lineSeparator);
                builder.append("Confirmed: ").append(count(summary, "total_confirmed")).append(lineSeparator);
                builder.append("Not confirmed: ").append(count(summary, "total_not_confirmed")).append(lineSeparator);
                builder.append("Errors: ").append(count(summary, "total_error")).append(lineSeparator);
                appendBugList(builder, workDir, summary);
            } catch (IOException exception) {
                builder.append("Could not read summary: ").append(exception.getMessage()).append(lineSeparator);
            }
        } else {
            builder.append("No summary found yet: ").append(summaryPath).append(lineSeparator);
        }

        if (Files.isRegularFile(logPath)) {
            builder.append(lineSeparator).append("Log tail: ").append(logPath).append(lineSeparator);
            builder.append(tail(logPath, 80));
        }

        return builder.toString();
    }

    private static String count(String json, String key) {
        Matcher matcher = Pattern.compile(String.format(COUNT_PATTERN.pattern(), Pattern.quote(key))).matcher(json);
        return matcher.find() ? matcher.group(1) : "0";
    }

    private static void appendBugList(StringBuilder builder, Path workDir, String summary) {
        String lineSeparator = System.lineSeparator();
        Matcher idMatcher = BUG_ID_PATTERN.matcher(summary);
        Matcher statusMatcher = BUG_STATUS_PATTERN.matcher(summary);
        int count = 0;
        while (idMatcher.find() && statusMatcher.find() && count < 20) {
            String id = idMatcher.group(1);
            String status = statusMatcher.group(1);
            Path report = workDir.resolve("bug_validation").resolve(id + ".md");
            builder.append("- ").append(id).append(" [").append(status).append("]");
            if (Files.isRegularFile(report)) {
                builder.append(" ").append(report);
            }
            builder.append(lineSeparator);
            count++;
        }
    }

    private static String tail(Path path, int maxLines) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int fromIndex = Math.max(0, lines.size() - maxLines);
            return String.join(System.lineSeparator(), lines.subList(fromIndex, lines.size())) + System.lineSeparator();
        } catch (IOException exception) {
            return "Could not read log: " + exception.getMessage() + System.lineSeparator();
        }
    }
}
