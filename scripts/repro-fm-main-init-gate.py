#!/usr/bin/env python3
"""Run the real FM-Agent main.py, but stop at OpenCode init/session creation.

The DevEco plugin starts FM-Agent through `/bin/bash -lc ... uv run python -u
main.py <target>`. This script uses the same outer command, with one controlled
difference: a temporary `opencode` wrapper is prepended to PATH. The wrapper
executes the real OpenCode binary, watches for `message=init` and
`message=created`, and kills OpenCode at `created` so later FM-Agent stages do
not run.
"""

from __future__ import annotations

import argparse
import os
import pathlib
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

real = os.environ["FM_INIT_GATE_REAL_OPENCODE"]
diag_dir = pathlib.Path(os.environ["FM_INIT_GATE_DIAG_DIR"])
deadline = float(os.environ.get("FM_INIT_GATE_INIT_DEADLINE", "8"))
attempt = os.environ.get("FM_INIT_GATE_ATTEMPT", "000")
diag_dir.mkdir(parents=True, exist_ok=True)
log_path = diag_dir / f"wrapper-{attempt}.opencode.log"
sentinel = diag_dir / f"wrapper-{attempt}.sentinel"

def redact(text):
    return re.sub(r"(?i)(API_KEY|TOKEN|SECRET|PASSWORD|KEY)=([^ \n]*)", r"\1=<redacted>", text)

def run_capture(argv, out, timeout=15):
    try:
        proc = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
        out.write_text(proc.stdout, encoding="utf-8", errors="replace")
    except Exception as exc:
        out.write_text(f"failed to run {' '.join(argv)}: {exc}\n", encoding="utf-8")

def collect(pid, reason):
    hang = diag_dir / f"wrapper-{attempt}-hang"
    hang.mkdir(parents=True, exist_ok=True)
    (hang / "reason.txt").write_text(reason + "\n", encoding="utf-8")
    run_capture(["ps", "-p", str(pid), "-o", "pid,ppid,pgid,stat,etime,command"], hang / "ps.txt")
    run_capture(["lsof", "-p", str(pid)], hang / "lsof.txt", timeout=20)
    ps_env = subprocess.run(["ps", "eww", "-p", str(pid)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (hang / "process-env.txt").write_text(redact(ps_env.stdout), encoding="utf-8", errors="replace")
    sample = shutil.which("sample")
    if sample:
        run_capture([sample, str(pid), "5", "1", "-file", str(hang / "opencode.sample")], hang / "sample-command.txt", timeout=12)

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
    started = time.monotonic()
    init_at = None
    while True:
        ready, _, _ = select.select([proc.stdout], [], [], 0.2)
        line = proc.stdout.readline() if ready else ""
        now = time.monotonic()
        if line:
            sys.stdout.write(line)
            sys.stdout.flush()
            log.write(line)
            log.flush()
            if "message=init" in line and init_at is None:
                init_at = now
                (diag_dir / f"wrapper-{attempt}.init").write_text(str(now - started), encoding="utf-8")
            if "message=created" in line:
                delta = "unknown" if init_at is None else f"{now - init_at:.6f}"
                sentinel.write_text(f"created init_to_created={delta} pid={proc.pid}\n", encoding="utf-8")
                terminate(proc)
                raise SystemExit(77)
        elif proc.poll() is not None:
            rest = proc.stdout.read()
            if rest:
                sys.stdout.write(rest)
                log.write(rest)
            sentinel.write_text(f"exit code={proc.returncode} pid={proc.pid}\n", encoding="utf-8")
            raise SystemExit(proc.returncode)

        if init_at is not None and now - init_at > deadline:
            reason = f"message=init seen, but message=created missing after {deadline}s"
            collect(proc.pid, reason)
            sentinel.write_text(f"init-hang pid={proc.pid} reason={reason}\n", encoding="utf-8")
            terminate(proc)
            raise SystemExit(78)
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


def _run_one(attempt: int, args: argparse.Namespace, wrapper_dir: pathlib.Path, diag_root: pathlib.Path) -> str:
    fm_agent_home = pathlib.Path(args.fm_agent_home).expanduser().resolve()
    target = pathlib.Path(args.target).expanduser().resolve()
    sentinel = diag_root / f"wrapper-{attempt:03d}.sentinel"
    command = (
        f'export PATH="{wrapper_dir}:$HOME/.local/bin:$HOME/.opencode/bin:$HOME/.bun/bin:/opt/homebrew/bin:/usr/local/bin:$PATH" && '
        "export PYTHONUNBUFFERED=1 PYTHONIOENCODING=UTF-8 && "
        "set -a && { [ ! -f .env ] || source .env; } && set +a && "
        "export OPENCODE_PRINT_LOGS=1 OPENCODE_LOG_LEVEL=INFO OPENCODE_PURE=1 && "
        "export OPENCODE_CONFIG_CONTENT='{\"snapshot\":false,\"plugin\":[]}' && "
        f"export OPENCODE_TIMEOUT_SECONDS={_quote(str(args.timeout_seconds))} && "
        f"export FM_INIT_GATE_REAL_OPENCODE={_quote(args.real_opencode)} && "
        f"export FM_INIT_GATE_DIAG_DIR={_quote(str(diag_root))} && "
        f"export FM_INIT_GATE_INIT_DEADLINE={_quote(str(args.init_deadline))} && "
        f"export FM_INIT_GATE_ATTEMPT={_quote(f'{attempt:03d}')} && "
        f"uv run python -u main.py {_quote(str(target))}"
    )

    print(f"[main attempt {attempt:03d}] command={command}", flush=True)
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
    started = time.monotonic()
    output_log = diag_root / f"main-{attempt:03d}.log"
    with output_log.open("w", encoding="utf-8", errors="replace") as log:
        while True:
            ready, _, _ = select.select([proc.stdout], [], [], 0.2)
            line = proc.stdout.readline() if ready else ""
            if line:
                print(line, end="", flush=True)
                log.write(line)
                log.flush()
            if sentinel.is_file():
                status = sentinel.read_text(encoding="utf-8", errors="replace").strip()
                print(f"[main attempt {attempt:03d}] gate={status}", flush=True)
                _terminate_process_group(proc)
                return status.split()[0]
            if proc.poll() is not None:
                rest = proc.stdout.read()
                if rest:
                    print(rest, end="", flush=True)
                    log.write(rest)
                print(f"[main attempt {attempt:03d}] exited code={proc.returncode}", flush=True)
                return f"main-exit:{proc.returncode}"
            if time.monotonic() - started > args.overall_deadline:
                print(f"[main attempt {attempt:03d}] overall timeout", flush=True)
                _terminate_process_group(proc)
                return "main-timeout"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fm-agent-home", default=DEFAULT_FM_AGENT_HOME)
    parser.add_argument("--target", default=DEFAULT_TARGET)
    parser.add_argument("--real-opencode", default=REAL_OPENCODE)
    parser.add_argument("--attempts", type=int, default=10)
    parser.add_argument("--init-deadline", type=float, default=8.0)
    parser.add_argument("--overall-deadline", type=float, default=40.0)
    parser.add_argument("--timeout-seconds", type=int, default=1500)
    parser.add_argument("--diag-dir", default="")
    args = parser.parse_args()

    fm_agent_home = pathlib.Path(args.fm_agent_home).expanduser().resolve()
    if not (fm_agent_home / "main.py").is_file():
        raise SystemExit(f"FM-Agent home must contain main.py: {fm_agent_home}")
    if not pathlib.Path(args.real_opencode).is_file():
        raise SystemExit(f"real opencode not found: {args.real_opencode}")

    diag_root = (
        pathlib.Path(args.diag_dir).expanduser().resolve()
        if args.diag_dir
        else pathlib.Path(tempfile.gettempdir()) / "fm-agent-opencode-init-gate" / _timestamp()
    )
    diag_root.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="fm-agent-opencode-gate.") as temp:
        wrapper_dir = pathlib.Path(temp)
        _write_wrapper(wrapper_dir / "opencode")
        print(f"FM-Agent home: {fm_agent_home}")
        print(f"Target: {pathlib.Path(args.target).expanduser().resolve()}")
        print(f"Wrapper dir: {wrapper_dir}")
        print(f"Diagnostics: {diag_root}")

        statuses: list[str] = []
        for attempt in range(1, args.attempts + 1):
            status = _run_one(attempt, args, wrapper_dir, diag_root)
            statuses.append(status)
            if status == "init-hang":
                break
            time.sleep(0.5)

    print("\nSummary:")
    print(f"  attempts: {len(statuses)}")
    print(f"  statuses: {', '.join(statuses)}")
    print(f"  diagnostics: {diag_root}")
    return 2 if "init-hang" in statuses else 0


if __name__ == "__main__":
    raise SystemExit(main())
