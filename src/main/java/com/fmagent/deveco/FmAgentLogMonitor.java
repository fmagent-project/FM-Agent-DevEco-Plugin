package com.fmagent.deveco;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class FmAgentLogMonitor {
    private static final long HEARTBEAT_INTERVAL_MILLIS = 30_000L;
    private static final long STALE_FILE_TOLERANCE_MILLIS = 1_000L;

    private final Map<Path, Long> offsets = new HashMap<>();
    private final Map<Path, Long> heartbeatTimes = new HashMap<>();
    private final long monitorStartMillis = System.currentTimeMillis();

    FmAgentLogMonitor(Path targetPath) {
        snapshotExistingLogs(targetPath);
    }

    String poll(Path targetPath, Process process) {
        StringBuilder builder = new StringBuilder();
        Path workDir = targetPath.resolve("fm_agent");
        pollFile(builder, workDir.resolve("fm_agent.log"), "fm_agent.log");
        pollFile(builder, workDir.resolve("trace").resolve("events.jsonl"), "trace/events.jsonl");
        pollOpencodePayloads(builder, workDir.resolve("trace").resolve("payloads"));
        appendProcessHeartbeat(builder, process);
        return builder.toString();
    }

    private void snapshotExistingLogs(Path targetPath) {
        Path workDir = targetPath.resolve("fm_agent");
        snapshotFile(workDir.resolve("fm_agent.log"));
        snapshotFile(workDir.resolve("trace").resolve("events.jsonl"));

        Path payloadDir = workDir.resolve("trace").resolve("payloads");
        if (!Files.isDirectory(payloadDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(payloadDir)) {
            files.filter(path -> path.getFileName().toString().endsWith("_opencode.log"))
                    .forEach(this::snapshotFile);
        } catch (IOException ignored) {
            // A missing or changing trace directory is normal during a fresh run.
        }
    }

    private void snapshotFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            offsets.put(path, Files.size(path));
        } catch (IOException ignored) {
            // The next poll will retry from the normal discovery path.
        }
    }

    private void pollOpencodePayloads(StringBuilder builder, Path payloadDir) {
        if (!Files.isDirectory(payloadDir)) {
            return;
        }

        try (Stream<Path> files = Files.list(payloadDir)) {
            List<Path> opencodeLogs = files
                    .filter(path -> path.getFileName().toString().endsWith("_opencode.log"))
                    .sorted()
                    .toList();
            for (Path log : opencodeLogs) {
                pollFile(builder, log, "opencode/" + log.getFileName());
                appendEmptyFileHeartbeat(builder, log);
            }
        } catch (IOException exception) {
            appendLine(builder, "[monitor] could not list opencode payloads: " + exception.getMessage());
        }
    }

    private void pollFile(StringBuilder builder, Path path, String label) {
        if (!Files.isRegularFile(path)) {
            return;
        }

        try {
            long length = Files.size(path);
            Long knownOffset = offsets.get(path);
            if (knownOffset == null && isStale(path)) {
                offsets.put(path, length);
                return;
            }

            long offset = knownOffset != null ? knownOffset : 0L;
            if (length < offset) {
                offset = 0L;
            }
            if (length == offset) {
                return;
            }

            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                file.seek(offset);
                byte[] bytes = new byte[(int) Math.min(length - offset, 128 * 1024)];
                int read = file.read(bytes);
                if (read > 0) {
                    appendLine(builder, "");
                    appendLine(builder, "--- " + label + " ---");
                    builder.append(new String(bytes, 0, read, StandardCharsets.UTF_8));
                    if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                        builder.append(System.lineSeparator());
                    }
                    offsets.put(path, offset + read);
                    return;
                }
            }
            offsets.put(path, length);
        } catch (IOException exception) {
            appendLine(builder, "[monitor] could not read " + label + ": " + exception.getMessage());
        }
    }

    private boolean isStale(Path path) throws IOException {
        long lastModifiedMillis = Files.getLastModifiedTime(path).toMillis();
        return lastModifiedMillis + STALE_FILE_TOLERANCE_MILLIS < monitorStartMillis;
    }

    private void appendEmptyFileHeartbeat(StringBuilder builder, Path path) {
        try {
            if (Files.size(path) != 0) {
                return;
            }
            long now = System.currentTimeMillis();
            long last = heartbeatTimes.getOrDefault(path, 0L);
            if (now - last < HEARTBEAT_INTERVAL_MILLIS) {
                return;
            }
            heartbeatTimes.put(path, now);
            appendLine(builder, "[monitor] waiting for opencode output: " + path + " is still 0 bytes");
        } catch (IOException ignored) {
            // The file can disappear during a fresh run; the next poll will rediscover it.
        }
    }

    private void appendProcessHeartbeat(StringBuilder builder, Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }

        long now = System.currentTimeMillis();
        Path key = Path.of("__process_heartbeat__");
        long last = heartbeatTimes.getOrDefault(key, 0L);
        if (now - last < HEARTBEAT_INTERVAL_MILLIS) {
            return;
        }
        heartbeatTimes.put(key, now);

        appendLine(builder, "[monitor] FM-Agent process is still running.");
        process.toHandle().descendants()
                .map(handle -> handle.info().commandLine().orElse("pid " + handle.pid()))
                .limit(8)
                .forEach(command -> appendLine(builder, "[monitor] child: " + command));
    }

    private static void appendLine(StringBuilder builder, String line) {
        builder.append(line).append(System.lineSeparator());
    }
}
