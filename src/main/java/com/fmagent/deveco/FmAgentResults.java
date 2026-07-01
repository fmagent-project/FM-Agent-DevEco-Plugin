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
    private static final Pattern BUG_OBJECT_PATTERN = Pattern.compile("\\{\\s*\"id\"\\s*:\\s*\"[^\"]+\".*?\\}", Pattern.DOTALL);
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

    static String emptyHtml() {
        return htmlDocument("<div class=\"empty\">No reasoning result loaded.</div>");
    }

    static String renderHtml(Path targetPath) {
        Path workDir = targetPath.resolve("fm_agent");
        LogicSummary logicSummary = readLogicSummary(workDir);
        BugSummary bugSummary = readBugSummary(workDir);
        return renderSummaryHtml(targetPath, workDir, logicSummary, bugSummary);
    }

    private static String renderSummary(Path targetPath, Path workDir, LogicSummary logicSummary, BugSummary bugSummary) {
        String lineSeparator = System.lineSeparator();
        StringBuilder builder = new StringBuilder();

        builder.append("=== Logic Reasoning Summary ===").append(lineSeparator);
        builder.append("Refreshed: ").append(LocalDateTime.now().format(REFRESH_TIME_FORMAT)).append(lineSeparator);
        builder.append("Target: ").append(targetPath).append(lineSeparator);
        builder.append("Logic reasoning results: ").append(workDir).append(lineSeparator);
        builder.append("Bug results: ").append(workDir.resolve("bug_validation")).append(lineSeparator);
        builder.append(lineSeparator);

        builder.append("=== Spec Logic Reasoning ===").append(lineSeparator);
        builder.append("Extracted functions: ").append(logicSummary.totalFunctions()).append(lineSeparator);
        builder.append("Functions reasoned about: ").append(logicSummary.reasonedFunctions()).append(lineSeparator);
        builder.append("Mismatch: ").append(logicSummary.mismatch()).append(lineSeparator);
        builder.append("Match: ").append(logicSummary.match()).append(lineSeparator);
        builder.append("No reasoning result: ").append(logicSummary.notReasonedFunctions()).append(lineSeparator);

        if (logicSummary.error() != null) {
            builder.append("Logic result read issue: ").append(logicSummary.error()).append(lineSeparator);
        } else if (logicSummary.resultFiles() == 0) {
            builder.append("No logic reasoning result files found yet.").append(lineSeparator);
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
        appendLinkList(builder, logicSummary.mismatchExamples(), lineSeparator);

        builder.append(lineSeparator);
        builder.append("Confirmed Bugs:").append(lineSeparator);
        appendLinkList(builder, bugSummary.confirmedBugs(), lineSeparator);

        return builder.toString();
    }

    private static String renderSummaryHtml(Path targetPath, Path workDir, LogicSummary logicSummary, BugSummary bugSummary) {
        String refreshed = LocalDateTime.now().format(REFRESH_TIME_FORMAT);
        StringBuilder builder = new StringBuilder();
        builder.append("<h2>Logic Reasoning Summary</h2>");
        builder.append("<table class=\"meta\">");
        appendRow(builder, "Refreshed", refreshed);
        appendRow(builder, "Target", targetPath.toString());
        appendRow(builder, "Logic reasoning results", workDir.toString());
        appendRow(builder, "Bug results", workDir.resolve("bug_validation").toString());
        builder.append("</table>");

        builder.append("<div class=\"section\"><h3>Spec Logic Reasoning</h3>");
        builder.append("<table class=\"metrics\">");
        appendMetric(builder, "Extracted functions", Integer.toString(logicSummary.totalFunctions()), "");
        appendMetric(builder, "Functions reasoned about", Integer.toString(logicSummary.reasonedFunctions()), "");
        appendMetric(builder, "Mismatch", Integer.toString(logicSummary.mismatch()), "mismatch");
        appendMetric(builder, "Match", Integer.toString(logicSummary.match()), "match");
        appendMetric(builder, "No reasoning result", Integer.toString(logicSummary.notReasonedFunctions()), "pending");
        builder.append("</table>");
        if (logicSummary.error() != null) {
            builder.append("<p class=\"issue\">Logic result read issue: ").append(escape(logicSummary.error())).append("</p>");
        } else if (logicSummary.resultFiles() == 0) {
            builder.append("<p class=\"muted\">No logic reasoning result files found yet.</p>");
        }
        builder.append("</div>");

        builder.append("<div class=\"section\"><h3>Bug Validation</h3>");
        if (bugSummary.available()) {
            builder.append("<table class=\"metrics\">");
            appendMetric(builder, "Reported", bugSummary.reported(), "");
            appendMetric(builder, "Confirmed", bugSummary.confirmed(), "mismatch");
            appendMetric(builder, "Not confirmed", bugSummary.notConfirmed(), "match");
            appendMetric(builder, "Errors", bugSummary.errors(), "pending");
            builder.append("</table>");
        } else if (bugSummary.error() != null) {
            builder.append("<p class=\"issue\">Could not read bug summary: ").append(escape(bugSummary.error())).append("</p>");
        } else {
            builder.append("<p class=\"muted\">No bug validation summary found yet.</p>");
        }
        builder.append("</div>");

        builder.append("<div class=\"section\"><h3>Mismatch functions</h3>");
        appendLinkListHtml(builder, logicSummary.mismatchExamples());
        builder.append("</div>");

        builder.append("<div class=\"section\"><h3>Confirmed Bugs</h3>");
        appendLinkListHtml(builder, bugSummary.confirmedBugs());
        builder.append("</div>");

        return htmlDocument(builder.toString());
    }

    private static void appendRow(StringBuilder builder, String label, String value) {
        builder.append("<tr><td class=\"label\">").append(escape(label)).append("</td><td><code>")
                .append(escape(value)).append("</code></td></tr>");
    }

    private static void appendMetric(StringBuilder builder, String label, String value, String valueClass) {
        builder.append("<tr><td>").append(escape(label)).append("</td><td class=\"number");
        if (!valueClass.isBlank()) {
            builder.append(" ").append(valueClass);
        }
        builder.append("\">").append(escape(value)).append("</td></tr>");
    }

    private static void appendLinkList(StringBuilder builder, List<ResultLink> items, String lineSeparator) {
        if (items.isEmpty()) {
            builder.append("- none").append(lineSeparator);
            return;
        }
        for (ResultLink item : items) {
            builder.append("- ").append(item.label()).append(lineSeparator);
        }
    }

    private static void appendLinkListHtml(StringBuilder builder, List<ResultLink> items) {
        if (items.isEmpty()) {
            builder.append("<p class=\"muted\">none</p>");
            return;
        }
        builder.append("<ul class=\"links\">");
        for (ResultLink item : items) {
            builder.append("<li><a href=\"").append(item.path().toUri()).append("\">")
                    .append(escape(item.label())).append("</a></li>");
        }
        builder.append("</ul>");
    }

    private static String htmlDocument(String body) {
        return """
                <html>
                <head>
                <style>
                body { font-family: sans-serif; font-size: 12px; margin: 10px; }
                h2 { margin: 0 0 8px 0; font-size: 17px; }
                h3 { margin: 0 0 6px 0; font-size: 13px; }
                table { border-collapse: collapse; width: 100%; }
                td { padding: 4px 8px 4px 0; vertical-align: top; }
                code { font-family: monospace; }
                .section { border: 1px solid #8a8a8a; padding: 8px; margin: 8px 0; }
                .meta .label { width: 90px; font-weight: bold; }
                .metrics td { border-bottom: 1px solid #8a8a8a; }
                .number { text-align: right; font-weight: bold; width: 80px; }
                .mismatch { color: #c0392b; }
                .match { color: #16833a; }
                .pending { color: #8a6d1d; }
                .issue { color: #c0392b; }
                .muted, .empty { color: #777777; }
                .links { margin: 0; padding-left: 18px; }
                .links li { margin: 3px 0; }
                a { color: #2f6feb; text-decoration: none; }
                </style>
                </head>
                <body>
                """ + body + """
                </body>
                </html>
                """;
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static LogicSummary readLogicSummary(Path workDir) {
        Path logicDir = workDir.resolve("logic_verification_results");
        Path extractedDir = workDir.resolve("extracted_functions");
        List<Path> resultFiles = listFiles(logicDir, ".json");
        List<Path> extractedFiles = listFiles(extractedDir, null);

        int match = 0;
        int mismatch = 0;
        List<ResultLink> mismatchExamples = new ArrayList<>();
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
                        mismatchExamples.add(new ResultLink(relative(logicDir, resultFile), resultFile));
                    }
                }
            } catch (IOException exception) {
                error = exception.getMessage();
            }
        }

        int reasonedFunctions = match + mismatch;
        int totalFunctions = Math.max(extractedFiles.size(), resultFiles.size());
        int notReasonedFunctions = Math.max(0, totalFunctions - reasonedFunctions);
        return new LogicSummary(
                totalFunctions,
                resultFiles.size(),
                reasonedFunctions,
                match,
                mismatch,
                notReasonedFunctions,
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
                    confirmedBugs(summary, workDir),
                    null);
        } catch (IOException exception) {
            return BugSummary.error(exception.getMessage());
        }
    }

    private static List<ResultLink> confirmedBugs(String summary, Path workDir) {
        Matcher objectMatcher = BUG_OBJECT_PATTERN.matcher(summary);
        List<ResultLink> confirmed = new ArrayList<>();
        while (objectMatcher.find()) {
            String object = objectMatcher.group();
            String id = stringField(object, "id");
            String status = stringField(object, "confirmation_status");
            if ("confirmed".equalsIgnoreCase(status)) {
                confirmed.add(new ResultLink(id, bugReportPath(workDir, id, stringField(object, "detail_file"))));
            }
        }
        return confirmed;
    }

    private static Path bugReportPath(Path workDir, String id, String detailFile) {
        String fallback = "bug_validation/" + id + ".md";
        String pathText = detailFile == null || detailFile.isBlank() ? fallback : detailFile;
        Path path = Path.of(pathText);
        if (path.isAbsolute()) {
            return path;
        }
        Path targetPath = workDir.getParent();
        if (targetPath == null) {
            return workDir.resolve(path).normalize();
        }
        return targetPath.resolve(path).normalize();
    }

    private static String stringField(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
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
        private final int reasonedFunctions;
        private final int match;
        private final int mismatch;
        private final int notReasonedFunctions;
        private final List<ResultLink> mismatchExamples;
        private final String error;

        private LogicSummary(int totalFunctions, int resultFiles, int reasonedFunctions, int match, int mismatch,
                             int notReasonedFunctions, List<ResultLink> mismatchExamples, String error) {
            this.totalFunctions = totalFunctions;
            this.resultFiles = resultFiles;
            this.reasonedFunctions = reasonedFunctions;
            this.match = match;
            this.mismatch = mismatch;
            this.notReasonedFunctions = notReasonedFunctions;
            this.mismatchExamples = mismatchExamples;
            this.error = error;
        }

        private int totalFunctions() {
            return totalFunctions;
        }

        private int resultFiles() {
            return resultFiles;
        }

        private int reasonedFunctions() {
            return reasonedFunctions;
        }

        private int match() {
            return match;
        }

        private int mismatch() {
            return mismatch;
        }

        private int notReasonedFunctions() {
            return notReasonedFunctions;
        }

        private List<ResultLink> mismatchExamples() {
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
        private final List<ResultLink> confirmedBugs;
        private final String error;

        private BugSummary(String json, String reported, String confirmed, String notConfirmed, String errors,
                           List<ResultLink> confirmedBugs, String error) {
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

        private List<ResultLink> confirmedBugs() {
            return confirmedBugs;
        }

        private String error() {
            return error;
        }
    }

    private static final class ResultLink {
        private final String label;
        private final Path path;

        private ResultLink(String label, Path path) {
            this.label = label;
            this.path = path;
        }

        private String label() {
            return label;
        }

        private Path path() {
            return path;
        }
    }
}
