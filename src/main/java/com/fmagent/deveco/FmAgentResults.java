package com.fmagent.deveco;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class FmAgentResults {
    private static final Pattern COUNT_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*(\\d+)");
    private static final Pattern BUG_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BUG_STATUS_PATTERN = Pattern.compile("\"confirmation_status\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERDICT_PATTERN = Pattern.compile("\"verdict\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter REFRESH_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private FmAgentResults() {
    }

    static String render(Path targetPath) {
        Path workDir = targetPath.resolve("fm_agent");
        LogicSummary logicSummary = readLogicSummary(workDir);
        BugSummary bugSummary = readBugSummary(workDir);
        return renderSummary(targetPath, workDir, logicSummary, bugSummary);
    }

    private static String renderSummary(Path targetPath, Path workDir, LogicSummary logicSummary, BugSummary bugSummary) {
        String lineSeparator = System.lineSeparator();
        StringBuilder builder = new StringBuilder();

        builder.append("=== Logic Verification Summary ===").append(lineSeparator);
        builder.append("Refreshed: ").append(LocalDateTime.now().format(REFRESH_TIME_FORMAT)).append(lineSeparator);
        builder.append("Target: ").append(targetPath).append(lineSeparator);
        builder.append("Logic results: ").append(workDir.resolve("logic_verification_results")).append(lineSeparator);
        builder.append("Bug results: ").append(workDir.resolve("bug_validation")).append(lineSeparator);
        builder.append(lineSeparator);

        builder.append("=== Spec Logic Verification ===").append(lineSeparator);
        builder.append("Extracted functions: ").append(logicSummary.totalFunctions()).append(lineSeparator);
        builder.append("Verified functions: ").append(logicSummary.verified()).append(lineSeparator);
        builder.append("Mismatch: ").append(logicSummary.mismatch()).append(lineSeparator);
        builder.append("Match: ").append(logicSummary.match()).append(lineSeparator);
        builder.append("Unverified: ").append(logicSummary.unverified()).append(lineSeparator);

        if (logicSummary.error() != null) {
            builder.append("Logic result read issue: ").append(logicSummary.error()).append(lineSeparator);
        } else if (logicSummary.resultFiles() == 0) {
            builder.append("No logic verification result files found yet.").append(lineSeparator);
        }
        builder.append(lineSeparator);

        builder.append("=== Bug Validation ===").append(lineSeparator);
        if (bugSummary.available()) {
            builder.append("Reported: ").append(bugSummary.reported()).append(lineSeparator);
            builder.append("Confirmed: ").append(bugSummary.confirmed()).append(lineSeparator);
            builder.append("Not confirmed: ").append(bugSummary.notConfirmed()).append(lineSeparator);
            builder.append("Errors: ").append(bugSummary.errors()).append(lineSeparator);
        } else if (bugSummary.error() != null) {
            builder.append("Could not read bug summary: ").append(bugSummary.error()).append(lineSeparator);
        } else {
            builder.append("No bug validation summary found yet.").append(lineSeparator);
        }

        builder.append(lineSeparator);
        builder.append("Mismatch functions:").append(lineSeparator);
        appendList(builder, logicSummary.mismatchExamples(), lineSeparator);

        builder.append(lineSeparator);
        builder.append("Confirmed Bugs:").append(lineSeparator);
        appendList(builder, bugSummary.confirmedBugs(), lineSeparator);

        return builder.toString();
    }

    private static void appendList(StringBuilder builder, List<String> items, String lineSeparator) {
        if (items.isEmpty()) {
            builder.append("- none").append(lineSeparator);
            return;
        }
        for (String item : items) {
            builder.append("- ").append(item).append(lineSeparator);
        }
    }

    private static LogicSummary readLogicSummary(Path workDir) {
        Path logicDir = workDir.resolve("logic_verification_results");
        Path extractedDir = workDir.resolve("extracted_functions");
        List<Path> resultFiles = listFiles(logicDir, ".json");
        List<Path> extractedFiles = listFiles(extractedDir, null);

        int match = 0;
        int mismatch = 0;
        List<String> mismatchExamples = new ArrayList<>();
        String error = null;

        for (Path resultFile : resultFiles) {
            try {
                String json = Files.readString(resultFile, StandardCharsets.UTF_8);
                String verdict = verdict(json);
                if ("MATCH".equals(verdict)) {
                    match++;
                } else if ("MISMATCH".equals(verdict)) {
                    mismatch++;
                    if (mismatchExamples.size() < 20) {
                        mismatchExamples.add(relative(logicDir, resultFile));
                    }
                }
            } catch (IOException exception) {
                error = exception.getMessage();
            }
        }

        int verified = match + mismatch;
        int totalFunctions = Math.max(extractedFiles.size(), resultFiles.size());
        int unverified = Math.max(0, totalFunctions - verified);
        return new LogicSummary(
                totalFunctions,
                resultFiles.size(),
                verified,
                match,
                mismatch,
                unverified,
                mismatchExamples,
                error);
    }

    private static BugSummary readBugSummary(Path workDir) {
        Path summaryPath = workDir.resolve("bug_validation").resolve("summary.json");
        if (!Files.isRegularFile(summaryPath)) {
            return BugSummary.missing();
        }

        try {
            String summary = Files.readString(summaryPath, StandardCharsets.UTF_8);
            return new BugSummary(
                    summary,
                    count(summary, "total_reported"),
                    count(summary, "total_confirmed"),
                    count(summary, "total_not_confirmed"),
                    count(summary, "total_error"),
                    confirmedBugs(summary),
                    null);
        } catch (IOException exception) {
            return BugSummary.error(exception.getMessage());
        }
    }

    private static List<String> confirmedBugs(String summary) {
        Matcher idMatcher = BUG_ID_PATTERN.matcher(summary);
        Matcher statusMatcher = BUG_STATUS_PATTERN.matcher(summary);
        List<String> confirmed = new ArrayList<>();
        while (idMatcher.find() && statusMatcher.find()) {
            String id = idMatcher.group(1);
            String status = statusMatcher.group(1);
            if ("confirmed".equalsIgnoreCase(status)) {
                confirmed.add(id);
            }
        }
        return confirmed;
    }

    private static List<Path> listFiles(Path root, String suffix) {
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> suffix == null || path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            return Collections.emptyList();
        }
    }

    private static String verdict(String json) {
        Matcher matcher = VERDICT_PATTERN.matcher(json);
        return matcher.find() ? matcher.group(1).trim().toUpperCase(Locale.ROOT) : "";
    }

    private static String count(String json, String key) {
        Matcher matcher = Pattern.compile(String.format(COUNT_PATTERN.pattern(), Pattern.quote(key))).matcher(json);
        return matcher.find() ? matcher.group(1) : "0";
    }

    private static String relative(Path root, Path path) {
        try {
            return root.relativize(path).toString();
        } catch (IllegalArgumentException exception) {
            return path.toString();
        }
    }

    private static final class LogicSummary {
        private final int totalFunctions;
        private final int resultFiles;
        private final int verified;
        private final int match;
        private final int mismatch;
        private final int unverified;
        private final List<String> mismatchExamples;
        private final String error;

        private LogicSummary(int totalFunctions, int resultFiles, int verified, int match, int mismatch,
                             int unverified, List<String> mismatchExamples, String error) {
            this.totalFunctions = totalFunctions;
            this.resultFiles = resultFiles;
            this.verified = verified;
            this.match = match;
            this.mismatch = mismatch;
            this.unverified = unverified;
            this.mismatchExamples = mismatchExamples;
            this.error = error;
        }

        private int totalFunctions() {
            return totalFunctions;
        }

        private int resultFiles() {
            return resultFiles;
        }

        private int verified() {
            return verified;
        }

        private int match() {
            return match;
        }

        private int mismatch() {
            return mismatch;
        }

        private int unverified() {
            return unverified;
        }

        private List<String> mismatchExamples() {
            return mismatchExamples;
        }

        private String error() {
            return error;
        }
    }

    private static final class BugSummary {
        private final String json;
        private final String reported;
        private final String confirmed;
        private final String notConfirmed;
        private final String errors;
        private final List<String> confirmedBugs;
        private final String error;

        private BugSummary(String json, String reported, String confirmed, String notConfirmed, String errors,
                           List<String> confirmedBugs, String error) {
            this.json = json;
            this.reported = reported;
            this.confirmed = confirmed;
            this.notConfirmed = notConfirmed;
            this.errors = errors;
            this.confirmedBugs = confirmedBugs;
            this.error = error;
        }

        private static BugSummary missing() {
            return new BugSummary(null, "0", "0", "0", "0", Collections.emptyList(), null);
        }

        private static BugSummary error(String error) {
            return new BugSummary(null, "0", "0", "0", "0", Collections.emptyList(), error);
        }

        private boolean available() {
            return json != null;
        }

        private String reported() {
            return reported;
        }

        private String confirmed() {
            return confirmed;
        }

        private String notConfirmed() {
            return notConfirmed;
        }

        private String errors() {
            return errors;
        }

        private List<String> confirmedBugs() {
            return confirmedBugs;
        }

        private String error() {
            return error;
        }
    }
}
