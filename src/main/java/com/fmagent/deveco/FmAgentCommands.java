package com.fmagent.deveco;

import java.nio.file.Path;

final class FmAgentCommands {
    private static final String MAC_PATH_EXPORT =
            "export PATH=\"$HOME/.local/bin:$HOME/.opencode/bin:$HOME/.bun/bin:/opt/homebrew/bin:/usr/local/bin:$PATH\"";

    private FmAgentCommands() {
    }

    static String installCommand() {
        return MAC_PATH_EXPORT + " && export PYTHONUNBUFFERED=1 PYTHONIOENCODING=UTF-8 && ./install_mac.sh";
    }

    static String environmentCheckCommand(Path targetPath, String opencodeTimeoutSeconds) {
        StringBuilder command = new StringBuilder(MAC_PATH_EXPORT);
        appendRuntimeEnvironment(command, opencodeTimeoutSeconds);
        command.append(" && echo '## Basic tools'");
        command.append(" && echo \"PWD=$PWD\"");
        command.append(" && echo \"HOME=$HOME\"");
        command.append(" && echo \"PATH=$PATH\"");
        command.append(" && command -v uv");
        command.append(" && uv --version");
        command.append(" && command -v python3");
        command.append(" && python3 --version");
        command.append(" && command -v opencode");
        command.append(" && opencode --version");
        command.append(" && test -f main.py");
        command.append(" && echo '## FM-Agent config'");
        appendPythonCommand(command, String.join("\n",
                "import config",
                "print('LLM_MODEL=' + config.LLM_MODEL)",
                "print('OPENCODE_MODEL_PROVIDER=' + config.OPENCODE_MODEL_PROVIDER)",
                "print('LLM_API_BASE_URL=' + config.LLM_API_BASE_URL)",
                "print('OPENCODE_TIMEOUT_SECONDS=' + str(config.OPENCODE_TIMEOUT_SECONDS))",
                "print('OPENCODE_PURE=' + str(config.OPENCODE_PURE))"));
        command.append(" && echo '## OpenCode config'");
        command.append(" && test -f \"$HOME/.config/opencode/opencode.json\"");
        command.append(" && python3 -m json.tool \"$HOME/.config/opencode/opencode.json\" >/dev/null");
        command.append(" && echo \"$HOME/.config/opencode/opencode.json is valid JSON\"");
        command.append(" && echo '## Required environment'");
        appendPythonCommand(command, String.join("\n",
                "import json, os, pathlib",
                "required = ['LLM_API_KEY', 'LLM_API_BASE_URL', 'LLM_MODEL', 'OPENCODE_MODEL_PROVIDER']",
                "missing = [key for key in required if not os.environ.get(key)]",
                "for key in required:",
                "    print(key + '=' + ('<set>' if os.environ.get(key) else '<missing>'))",
                "if missing:",
                "    raise SystemExit('missing required env var(s): ' + ', '.join(missing))",
                "provider = os.environ['OPENCODE_MODEL_PROVIDER']",
                "model = os.environ['LLM_MODEL']",
                "config_path = pathlib.Path.home() / '.config/opencode/opencode.json'",
                "config = json.loads(config_path.read_text())",
                "providers = config.get('provider') or {}",
                "if provider not in providers:",
                "    raise SystemExit('OpenCode provider not configured: ' + provider)",
                "models = (providers[provider].get('models') or {})",
                "if model not in models:",
                "    raise SystemExit('OpenCode model not configured for ' + provider + ': ' + model)",
                "api_key_ref = ((providers[provider].get('options') or {}).get('apiKey') or '')",
                "if api_key_ref.startswith('{env:') and api_key_ref.endswith('}'):",
                "    env_key = api_key_ref[5:-1]",
                "    print('OpenCode apiKey env ' + env_key + '=' + ('<set>' if os.environ.get(env_key) else '<missing>'))",
                "    if not os.environ.get(env_key):",
                "        raise SystemExit('OpenCode apiKey env var is missing: ' + env_key)",
                "print('OpenCode provider/model OK: ' + provider + '/' + model)"));
        command.append(" && echo '## API smoke test'");
        appendPythonCommand(command, String.join("\n",
                "import json, os, urllib.request",
                "base = os.environ.get('LLM_API_BASE_URL') or os.environ.get('BASE_URL')",
                "model = os.environ.get('LLM_MODEL') or os.environ.get('MODEL_ID')",
                "key = os.environ.get('LLM_API_KEY') or os.environ.get('API_KEY') or ''",
                "if not base or not model:",
                "    raise SystemExit('missing LLM_API_BASE_URL/BASE_URL or LLM_MODEL/MODEL_ID')",
                "req = urllib.request.Request(base.rstrip('/') + '/chat/completions')",
                "req.add_header('Content-Type', 'application/json')",
                "req.add_header('Authorization', 'Bearer ' + key)",
                "payload = {'model': model, 'messages': [{'role': 'user', 'content': 'reply with OK only'}], 'max_tokens': 16, 'temperature': 0}",
                "with urllib.request.urlopen(req, data=json.dumps(payload).encode(), timeout=60) as response:",
                "    body = response.read().decode(errors='replace')",
                "print('API smoke test OK, bytes=' + str(len(body)))"));
        command.append(" && echo '## OpenCode smoke test (--pure)'");
        appendPythonCommand(command, String.join("\n",
                "import os, pathlib, subprocess, sys, threading, time",
                "provider = os.environ.get('OPENCODE_MODEL_PROVIDER', 'openrouter')",
                "model = os.environ.get('LLM_MODEL') or os.environ.get('MODEL_ID')",
                "target = " + pythonStringLiteral(targetPath.toString()),
                "if not model:",
                "    raise SystemExit('missing LLM_MODEL/MODEL_ID')",
                "cmd = ['opencode', '--pure', '--print-logs', '--log-level', 'INFO', 'run', '--model', provider + '/' + model, 'reply with OK only']",
                "print(' '.join(cmd))",
                "env = os.environ.copy()",
                "env['PWD'] = target",
                "timeout = int(os.environ.get('OPENCODE_TIMEOUT_SECONDS') or '120')",
                "proc = subprocess.Popen(cmd, cwd=target, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')",
                "def copy_output():",
                "    assert proc.stdout is not None",
                "    for line in proc.stdout:",
                "        print(line, end='', flush=True)",
                "thread = threading.Thread(target=copy_output, daemon=True)",
                "thread.start()",
                "deadline = time.monotonic() + timeout",
                "while proc.poll() is None and time.monotonic() < deadline:",
                "    time.sleep(0.5)",
                "if proc.poll() is None:",
                "    print('OpenCode smoke test timed out after ' + str(timeout) + 's; pid=' + str(proc.pid), flush=True)",
                "    log_path = pathlib.Path.home() / '.local/share/opencode/log/opencode.log'",
                "    if log_path.is_file():",
                "        print('--- ~/.local/share/opencode/log/opencode.log tail ---', flush=True)",
                "        lines = log_path.read_text(errors='replace').splitlines()[-80:]",
                "        print('\\n'.join(lines), flush=True)",
                "    proc.terminate()",
                "    try:",
                "        proc.wait(timeout=10)",
                "    except subprocess.TimeoutExpired:",
                "        proc.kill()",
                "        proc.wait()",
                "    raise SystemExit(124)",
                "thread.join(timeout=5)",
                "if proc.returncode != 0:",
                "    raise SystemExit(proc.returncode)"));
        return command.toString();
    }

    static String verifyCommand(Path targetPath, boolean resume, boolean isolate, String opencodeTimeoutSeconds) {
        StringBuilder command = new StringBuilder(MAC_PATH_EXPORT);
        appendRuntimeEnvironment(command, opencodeTimeoutSeconds);
        command.append(" && uv run python -u main.py ");
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

    private static void appendPythonCommand(StringBuilder command, String code) {
        command.append(" && uv run python -c ");
        command.append(shellQuote(code));
    }

    private static void appendRuntimeEnvironment(StringBuilder command, String opencodeTimeoutSeconds) {
        command.append(" && export PYTHONUNBUFFERED=1 PYTHONIOENCODING=UTF-8");
        command.append(" && set -a && { [ ! -f .env ] || source .env; } && set +a");
        command.append(" && export OPENCODE_PRINT_LOGS=1 OPENCODE_LOG_LEVEL=INFO OPENCODE_PURE=1");
        command.append(" && export OPENCODE_CONFIG_CONTENT=");
        command.append(shellQuote("{\"snapshot\":false,\"plugin\":[]}"));
        if (opencodeTimeoutSeconds != null && !opencodeTimeoutSeconds.isBlank()) {
            command.append(" && export OPENCODE_TIMEOUT_SECONDS=");
            command.append(shellQuote(opencodeTimeoutSeconds.trim()));
        }
    }

    private static String pythonStringLiteral(String value) {
        return "'" + value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "'";
    }
}
