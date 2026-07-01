package com.fmagent.deveco;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FmAgentEnvFile {
    static final String LLM_API_KEY = "LLM_API_KEY";
    static final String LLM_API_BASE_URL = "LLM_API_BASE_URL";
    static final String LLM_MODEL = "LLM_MODEL";
    static final String OPENCODE_MODEL_PROVIDER = "OPENCODE_MODEL_PROVIDER";
    static final String DEFAULT_LLM_API_BASE_URL = "https://openrouter.ai/api/v1";
    static final String DEFAULT_LLM_MODEL = "anthropic/claude-sonnet-4.6";
    static final String DEFAULT_OPENCODE_MODEL_PROVIDER = "openrouter";

    private static final Pattern ASSIGNMENT_PATTERN =
            Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)\\s*$");

    private FmAgentEnvFile() {
    }

    static Map<String, String> read(Path envPath) throws IOException {
        Map<String, String> values = defaultValues();
        if (!Files.isRegularFile(envPath)) {
            return values;
        }
        for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
            Matcher matcher = ASSIGNMENT_PATTERN.matcher(line);
            if (matcher.matches()) {
                values.put(matcher.group(1), parseValue(matcher.group(2)));
            }
        }
        return values;
    }

    static void write(Path envPath, Map<String, String> updates) throws IOException {
        Files.createDirectories(envPath.getParent());
        List<String> lines = Files.isRegularFile(envPath)
                ? Files.readAllLines(envPath, StandardCharsets.UTF_8)
                : initialLines();
        List<String> merged = new ArrayList<>();
        Set<String> written = new LinkedHashSet<>();

        for (String line : lines) {
            Matcher matcher = ASSIGNMENT_PATTERN.matcher(line);
            if (matcher.matches() && updates.containsKey(matcher.group(1))) {
                String key = matcher.group(1);
                merged.add(key + "=" + shellValue(updates.get(key)));
                written.add(key);
            } else {
                merged.add(line);
            }
        }

        if (!written.containsAll(updates.keySet())) {
            if (!merged.isEmpty() && !merged.get(merged.size() - 1).isBlank()) {
                merged.add("");
            }
            for (Map.Entry<String, String> entry : updates.entrySet()) {
                if (!written.contains(entry.getKey())) {
                    merged.add(entry.getKey() + "=" + shellValue(entry.getValue()));
                }
            }
        }

        Files.write(envPath, merged, StandardCharsets.UTF_8);
    }

    static Map<String, String> defaultValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(LLM_API_KEY, "");
        values.put(LLM_API_BASE_URL, DEFAULT_LLM_API_BASE_URL);
        values.put(LLM_MODEL, DEFAULT_LLM_MODEL);
        values.put(OPENCODE_MODEL_PROVIDER, DEFAULT_OPENCODE_MODEL_PROVIDER);
        return values;
    }

    static String displayValue(Map<String, String> values, String key) {
        return values.getOrDefault(key, defaultValues().getOrDefault(key, ""));
    }

    private static List<String> initialLines() {
        return new ArrayList<>(List.of(
                "# fm-agent runtime config - gitignored, do not commit.",
                "# Written by FM-Agent DevEco Plugin.",
                ""));
    }

    private static String parseValue(String rawValue) {
        String value = stripInlineComment(rawValue.trim());
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return unescapeDoubleQuoted(value.substring(1, value.length() - 1));
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String stripInlineComment(String value) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (character == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (character == '#' && !inSingleQuote && !inDoubleQuote
                    && (index == 0 || Character.isWhitespace(value.charAt(index - 1)))) {
                return value.substring(0, index).trim();
            }
        }
        return value;
    }

    private static String unescapeDoubleQuoted(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaping) {
                builder.append(switch (character) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> character;
                });
                escaping = false;
            } else if (character == '\\') {
                escaping = true;
            } else {
                builder.append(character);
            }
        }
        if (escaping) {
            builder.append('\\');
        }
        return builder.toString();
    }

    private static String shellValue(String value) {
        String normalized = value == null ? "" : value;
        return "\"" + normalized
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`")
                .replace("\n", "\\n")
                .replace("\r", "") + "\"";
    }
}
