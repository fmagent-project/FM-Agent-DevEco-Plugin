package com.fmagent.deveco;

import java.nio.file.Path;

final class FmAgentCommands {
    private static final String MAC_PATH_EXPORT =
            "export PATH=\"$HOME/.local/bin:$HOME/.opencode/bin:$HOME/.bun/bin:/opt/homebrew/bin:/usr/local/bin:$PATH\"";

    private FmAgentCommands() {
    }

    static String installCommand() {
        return MAC_PATH_EXPORT + " && ./install_mac.sh";
    }

    static String verifyCommand(Path targetPath, boolean resume, boolean isolate) {
        StringBuilder command = new StringBuilder(MAC_PATH_EXPORT);
        command.append(" && uv run python main.py ");
        command.append(shellQuote(targetPath.toString()));
        if (resume) {
            command.append(" --resume");
        }
        if (isolate) {
            command.append(" --isolate");
        }
        return command.toString();
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
