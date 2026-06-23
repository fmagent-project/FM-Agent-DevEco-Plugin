#!/usr/bin/env python3
"""Capture OpenCode process state shortly after `message=created`.

This mirrors the DevEco plugin launch path:

    /bin/bash -lc "... uv run python -u main.py <target>"

It prepends a temporary `opencode` wrapper to PATH. The wrapper runs the real
OpenCode binary, waits for `message=created`, sleeps for a configurable delay,
captures `sample`, `lsof`, and `ps env`, then terminates OpenCode and the parent
FM-Agent run. This keeps the diagnostic focused on the post-created window.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import select
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import textwrap
import time
from datetime import datetime, timezone


DEFAULT_FM_AGENT_HOME = "/Users/lianganran/codes/2_SJTU_code/FM-agent/FM-Agent-Internal"
DEFAULT_TARGET = "/Users/lianganran/codes/2_SJTU_code/FM-agent/ExampleCppApp"
REAL_OPENCODE = "/Users/lianganran/.opencode/bin/opencode"


def _timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def _quote(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def _write_wrapper(path: pathlib.Path) -> None:
    wrapper = r'''
#!/usr/bin/env python3
import os
import pathlib
import re
import select
import shutil
import signal
import subprocess
import sys
import time

real = os.environ["FM_POST_CREATED_REAL_OPENCODE"]
diag_dir = pathlib.Path(os.environ["FM_POST_CREATED_DIAG_DIR"])
delay = float(os.environ.get("FM_POST_CREATED_DELAY", "10"))
attempt = os.environ.get("FM_POST_CREATED_ATTEMPT", "001")
diag_dir.mkdir(parents=True, exist_ok=True)
log_path = diag_dir / f"wrapper-{attempt}.opencode.log"
sentinel = diag_dir / f"wrapper-{attempt}.sentinel"

def redact(text):
    return re.sub(r"(?i)(API_KEY|TOKEN|SECRET|PASSWORD|KEY)=([^ \n]*)", r"\1=<redacted>", text)

def run_capture(argv, out, timeout=20):
    try:
        proc = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
        out.write_text(proc.stdout, encoding="utf-8", errors="replace")
    except Exception as exc:
        out.write_text(f"failed to run {' '.join(argv)}: {exc}\n", encoding="utf-8")

def collect(pid, reason):
    snap = diag_dir / f"wrapper-{attempt}-post-created"
    snap.mkdir(parents=True, exist_ok=True)
    (snap / "reason.txt").write_text(reason + "\n", encoding="utf-8")
    run_capture(["ps", "-p", str(pid), "-o", "pid,ppid,pgid,stat,etime,%cpu,%mem,command"], snap / "ps.txt")
    run_capture(["lsof", "-p", str(pid)], snap / "lsof.txt", timeout=25)
    ps_env = subprocess.run(["ps", "eww", "-p", str(pid)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (snap / "process-env.txt").write_text(redact(ps_env.stdout), encoding="utf-8", errors="replace")
    global_log = pathlib.Path.home() / ".local/share/opencode/log/opencode.log"
    if global_log.is_file():
        lines = global_log.read_text(encoding="utf-8", errors="replace").splitlines()[-300:]
        (snap / "opencode-global-tail.log").write_text("\n".join(lines) + "\n", encoding="utf-8")
    sample = shutil.which("sample")
    if sample:
        run_capture([sample, str(pid), "5", "1", "-file", str(snap / "opencode.sample")], snap / "sample-command.txt", timeout=15)
    else:
        (snap / "sample-command.txt").write_text("sample command not found\n", encoding="utf-8")
    return snap

def terminate(proc):
    if proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        proc.wait(timeout=5)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        return
    proc.wait(timeout=5)

cmd = [real] + sys.argv[1:]
with log_path.open("w", encoding="utf-8", errors="replace") as log:
    log.write("$ " + " ".join(cmd) + "\n")
    log.flush()
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        start_new_session=True,
    )
    assert proc.stdout is not None
    created_at = None
    captured = False
    while True:
        ready, _, _ = select.select([proc.stdout], [], [], 0.2)
        line = proc.stdout.readline() if ready else ""
        now = time.monotonic()
        if line:
            sys.stdout.write(line)
            sys.stdout.flush()
            log.write(line)
            log.flush()
            if "message=created" in line and created_at is None:
                created_at = now
                (diag_dir / f"wrapper-{attempt}.created").write_text(line, encoding="utf-8")
        elif proc.poll() is not None:
            rest = proc.stdout.read()
            if rest:
                sys.stdout.write(rest)
                log.write(rest)
            sentinel.write_text(f"exit-before-capture code={proc.returncode} pid={proc.pid}\n", encoding="utf-8")
            raise SystemExit(proc.returncode)

        if created_at is not None and not captured and now - created_at >= delay:
            reason = f"captured {delay}s after message=created; pid={proc.pid}"
            snap = collect(proc.pid, reason)
            sentinel.write_text(f"captured pid={proc.pid} dir={snap}\n", encoding="utf-8")
            captured = True
            terminate(proc)
            raise SystemExit(79)
'''
    path.write_text(textwrap.dedent(wrapper).lstrip(), encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


def _terminate_process_group(proc: subprocess.Popen[str]) -> None:
    if proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        proc.wait(timeout=5)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        return
    proc.wait(timeout=5)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fm-agent-home", default=DEFAULT_FM_AGENT_HOME)
    parser.add_argument("--target", default=DEFAULT_TARGET)
    parser.add_argument("--real-opencode", default=REAL_OPENCODE)
    parser.add_argument("--delay", type=float, default=10.0)
    parser.add_argument("--overall-deadline", type=float, default=90.0)
    parser.add_argument("--timeout-seconds", type=int, default=1500)
    parser.add_argument("--diag-dir", default="")
    args = parser.parse_args()

    fm_agent_home = pathlib.Path(args.fm_agent_home).expanduser().resolve()
    target = pathlib.Path(args.target).expanduser().resolve()
    if not (fm_agent_home / "main.py").is_file():
        raise SystemExit(f"FM-Agent home must contain main.py: {fm_agent_home}")
    if not pathlib.Path(args.real_opencode).is_file():
        raise SystemExit(f"real opencode not found: {args.real_opencode}")

    diag_root = (
        pathlib.Path(args.diag_dir).expanduser().resolve()
        if args.diag_dir
        else pathlib.Path(tempfile.gettempdir()) / "fm-agent-opencode-post-created" / _timestamp()
    )
    diag_root.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="fm-agent-opencode-post-created.") as temp:
        wrapper_dir = pathlib.Path(temp)
        _write_wrapper(wrapper_dir / "opencode")
        command = (
            f'export PATH="{wrapper_dir}:$HOME/.local/bin:$HOME/.opencode/bin:$HOME/.bun/bin:/opt/homebrew/bin:/usr/local/bin:$PATH" && '
            "export PYTHONUNBUFFERED=1 PYTHONIOENCODING=UTF-8 && "
            "set -a && { [ ! -f .env ] || source .env; } && set +a && "
            "export OPENCODE_PRINT_LOGS=1 OPENCODE_LOG_LEVEL=INFO OPENCODE_PURE=1 && "
            "export OPENCODE_CONFIG_CONTENT='{\"snapshot\":false,\"plugin\":[]}' && "
            f"export OPENCODE_TIMEOUT_SECONDS={_quote(str(args.timeout_seconds))} && "
            f"export FM_POST_CREATED_REAL_OPENCODE={_quote(args.real_opencode)} && "
            f"export FM_POST_CREATED_DIAG_DIR={_quote(str(diag_root))} && "
            f"export FM_POST_CREATED_DELAY={_quote(str(args.delay))} && "
            "export FM_POST_CREATED_ATTEMPT='001' && "
            f"uv run python -u main.py {_quote(str(target))}"
        )

        print(f"FM-Agent home: {fm_agent_home}")
        print(f"Target: {target}")
        print(f"Diagnostics: {diag_root}")
        print(f"Command: {command}")

        proc = subprocess.Popen(
            ["/bin/bash", "-lc", command],
            cwd=fm_agent_home,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            start_new_session=True,
        )
        assert proc.stdout is not None
        output_log = diag_root / "main.log"
        sentinel = diag_root / "wrapper-001.sentinel"
        started = time.monotonic()
        with output_log.open("w", encoding="utf-8", errors="replace") as log:
            while True:
                ready, _, _ = select.select([proc.stdout], [], [], 0.2)
                line = proc.stdout.readline() if ready else ""
                if line:
                    print(line, end="", flush=True)
                    log.write(line)
                    log.flush()
                if sentinel.is_file():
                    print(f"Sentinel: {sentinel.read_text(encoding='utf-8', errors='replace').strip()}")
                    _terminate_process_group(proc)
                    return 0
                if proc.poll() is not None:
                    rest = proc.stdout.read()
                    if rest:
                        print(rest, end="", flush=True)
                        log.write(rest)
                    print(f"FM-Agent exited before sentinel, code={proc.returncode}")
                    return proc.returncode or 0
                if time.monotonic() - started > args.overall_deadline:
                    print(f"overall timeout after {args.overall_deadline}s")
                    _terminate_process_group(proc)
                    return 124


if __name__ == "__main__":
    raise SystemExit(main())
