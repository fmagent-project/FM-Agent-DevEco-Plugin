#!/usr/bin/env python3
"""Reproduce and diagnose OpenCode hangs between `message=init` and `message=created`.

This intentionally mirrors the DevEco plugin runtime environment, but it only
starts the first Stage 1 OpenCode command and kills it as soon as the OpenCode
session is created. It does not run the whole FM-Agent pipeline.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone


DEFAULT_FM_AGENT_HOME = "/Users/lianganran/codes/2_SJTU_code/FM-agent/FM-Agent-Internal"
DEFAULT_TARGET = "/Users/lianganran/codes/2_SJTU_code/FM-agent/ExampleCppApp"
PLUGIN_PATH_PREFIX = "$HOME/.local/bin:$HOME/.opencode/bin:$HOME/.bun/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
SETUP_PROMPT = (
    "Follow the instructions in the attached file. IMPORTANT: The fm_agent/ directory is NOT part of the "
    "project source code. It is a workspace for storing your output files only. Do NOT include fm_agent/ "
    "paths in phases.json. Do NOT modify any existing project files."
)


def _timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def _read_plugin_env(fm_agent_home: pathlib.Path, timeout_seconds: int) -> dict[str, str]:
    command = (
        f'export PATH="{PLUGIN_PATH_PREFIX}" && '
        "export PYTHONUNBUFFERED=1 PYTHONIOENCODING=UTF-8 && "
        "set -a && { [ ! -f .env ] || source .env; } && set +a && "
        "export OPENCODE_PRINT_LOGS=1 OPENCODE_LOG_LEVEL=INFO OPENCODE_PURE=1 && "
        "export OPENCODE_CONFIG_CONTENT='{\"snapshot\":false,\"plugin\":[]}' && "
        f"export OPENCODE_TIMEOUT_SECONDS='{timeout_seconds}' && "
        "env -0"
    )
    proc = subprocess.run(
        ["/bin/bash", "-lc", command],
        cwd=fm_agent_home,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr.decode("utf-8", errors="replace"))
        raise SystemExit(proc.returncode)

    env: dict[str, str] = {}
    for item in proc.stdout.split(b"\0"):
        if not item or b"=" not in item:
            continue
        key, value = item.split(b"=", 1)
        env[key.decode("utf-8", errors="replace")] = value.decode("utf-8", errors="replace")
    return env


def _redact_env(text: str) -> str:
    return re.sub(r"(?i)(API_KEY|TOKEN|SECRET|PASSWORD|KEY)=([^ \n]*)", r"\1=<redacted>", text)


def _run_capture(args: list[str], path: pathlib.Path, timeout: int = 15) -> None:
    try:
        proc = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
        path.write_text(proc.stdout, encoding="utf-8", errors="replace")
    except Exception as exc:  # noqa: BLE001 - diagnostics must not mask the hang
        path.write_text(f"failed to run {' '.join(args)}: {exc}\n", encoding="utf-8")


def _collect_hang_diagnostics(pid: int, diag_dir: pathlib.Path, env: dict[str, str], reason: str) -> None:
    diag_dir.mkdir(parents=True, exist_ok=True)
    (diag_dir / "reason.txt").write_text(reason + "\n", encoding="utf-8")

    _run_capture(["ps", "-p", str(pid), "-o", "pid,ppid,pgid,stat,etime,command"], diag_dir / "ps.txt")
    _run_capture(["lsof", "-p", str(pid)], diag_dir / "lsof.txt", timeout=20)

    ps_env = subprocess.run(["ps", "eww", "-p", str(pid)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (diag_dir / "process-env.txt").write_text(_redact_env(ps_env.stdout), encoding="utf-8", errors="replace")

    global_log = pathlib.Path.home() / ".local/share/opencode/log/opencode.log"
    if global_log.is_file():
        lines = global_log.read_text(encoding="utf-8", errors="replace").splitlines()[-300:]
        (diag_dir / "opencode-global-tail.log").write_text("\n".join(lines) + "\n", encoding="utf-8")

    sample = shutil.which("sample")
    if sample:
        _run_capture([sample, str(pid), "5", "1", "-file", str(diag_dir / "opencode.sample")], diag_dir / "sample-command.txt", timeout=12)

    env_lines = []
    for key in sorted(env):
        value = env[key]
        if re.search(r"(?i)(API_KEY|TOKEN|SECRET|PASSWORD|KEY)", key):
            value = "<redacted>"
        env_lines.append(f"{key}={value}")
    (diag_dir / "launch-env.txt").write_text("\n".join(env_lines) + "\n", encoding="utf-8")


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


def _run_one(attempt: int, args: argparse.Namespace, env: dict[str, str], diag_root: pathlib.Path) -> tuple[str, float | None]:
    target = pathlib.Path(args.target).expanduser().resolve()
    workflow = target / "fm_agent/workflow_setup_extract.md"
    if not workflow.is_file():
        raise SystemExit(f"missing workflow file: {workflow}")

    provider = env.get("OPENCODE_MODEL_PROVIDER", "deepseek")
    model = env.get("LLM_MODEL") or env.get("MODEL_ID") or "deepseek-v4-flash"
    event_id = f"diag_init_{_timestamp()}_{attempt:03d}"
    trace_dir = target / "fm_agent/trace/opencode"
    trace_dir.mkdir(parents=True, exist_ok=True)

    child_env = env.copy()
    child_env["TRACE_DIR"] = str(trace_dir)
    child_env["TRACE_FILENAME"] = event_id
    child_env["PWD"] = str(target)
    child_env.setdefault("OPENCODE_CONFIG_CONTENT", '{"snapshot":false,"plugin":[]}')

    cmd = [
        "opencode",
        "--pure",
        "--print-logs",
        "--log-level",
        "INFO",
        "run",
        "--model",
        f"{provider}/{model}",
        "--file",
        str(workflow),
        "--",
        SETUP_PROMPT,
    ]

    payload_log = diag_root / f"attempt-{attempt:03d}.opencode.log"
    init_at: float | None = None
    started = time.monotonic()

    with payload_log.open("w", encoding="utf-8", errors="replace") as log_file:
        proc = subprocess.Popen(
            cmd,
            cwd=target,
            env=child_env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            start_new_session=True,
        )
        assert proc.stdout is not None
        print(f"[attempt {attempt:03d}] pid={proc.pid} command={' '.join(cmd)}", flush=True)

        while True:
            ready, _, _ = select.select([proc.stdout], [], [], 0.2)
            line = proc.stdout.readline() if ready else ""
            now = time.monotonic()
            if line:
                log_file.write(line)
                log_file.flush()
                if "message=init" in line and init_at is None:
                    init_at = now
                    print(f"[attempt {attempt:03d}] saw init at {now - started:.3f}s", flush=True)
                if "message=created" in line:
                    delta = None if init_at is None else now - init_at
                    print(f"[attempt {attempt:03d}] saw created at {now - started:.3f}s init_to_created={delta}", flush=True)
                    _terminate_process_group(proc)
                    return "created", delta
            elif proc.poll() is not None:
                rest = proc.stdout.read()
                if rest:
                    log_file.write(rest)
                print(f"[attempt {attempt:03d}] exited before created, code={proc.returncode}", flush=True)
                return f"exit:{proc.returncode}", None

            if init_at is not None and now - init_at > args.init_deadline:
                hang_dir = diag_root / f"attempt-{attempt:03d}-hang"
                print(f"[attempt {attempt:03d}] init hang after {args.init_deadline}s; collecting {hang_dir}", flush=True)
                _collect_hang_diagnostics(
                    proc.pid,
                    hang_dir,
                    child_env,
                    f"message=init was seen, but message=created did not appear within {args.init_deadline}s",
                )
                _terminate_process_group(proc)
                return "init-hang", now - init_at

            if now - started > args.overall_deadline:
                hang_dir = diag_root / f"attempt-{attempt:03d}-overall-timeout"
                print(f"[attempt {attempt:03d}] no created after {args.overall_deadline}s; collecting {hang_dir}", flush=True)
                _collect_hang_diagnostics(
                    proc.pid,
                    hang_dir,
                    child_env,
                    f"message=created did not appear within {args.overall_deadline}s; init_seen={init_at is not None}",
                )
                _terminate_process_group(proc)
                return "overall-timeout", None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fm-agent-home", default=DEFAULT_FM_AGENT_HOME)
    parser.add_argument("--target", default=DEFAULT_TARGET)
    parser.add_argument("--attempts", type=int, default=20)
    parser.add_argument("--init-deadline", type=float, default=10.0)
    parser.add_argument("--overall-deadline", type=float, default=30.0)
    parser.add_argument("--timeout-seconds", type=int, default=1500)
    parser.add_argument("--diag-dir", default="")
    args = parser.parse_args()

    fm_agent_home = pathlib.Path(args.fm_agent_home).expanduser().resolve()
    target = pathlib.Path(args.target).expanduser().resolve()
    if not (fm_agent_home / "main.py").is_file():
        raise SystemExit(f"FM-Agent home must contain main.py: {fm_agent_home}")
    if not target.is_dir():
        raise SystemExit(f"target project does not exist: {target}")

    env = _read_plugin_env(fm_agent_home, args.timeout_seconds)
    diag_root = (
        pathlib.Path(args.diag_dir).expanduser().resolve()
        if args.diag_dir
        else pathlib.Path(tempfile.gettempdir()) / "fm-agent-opencode-init-repro" / _timestamp()
    )
    diag_root.mkdir(parents=True, exist_ok=True)

    print(f"FM-Agent home: {fm_agent_home}")
    print(f"Target: {target}")
    print(f"Diagnostics: {diag_root}")
    print(f"OpenCode: {shutil.which('opencode', path=env.get('PATH'))}")
    print(f"Model: {env.get('OPENCODE_MODEL_PROVIDER')}/{env.get('LLM_MODEL') or env.get('MODEL_ID')}")
    print(f"Init deadline: {args.init_deadline}s")

    results: list[tuple[str, float | None]] = []
    for attempt in range(1, args.attempts + 1):
        status, delta = _run_one(attempt, args, env, diag_root)
        results.append((status, delta))
        if status == "init-hang":
            break
        time.sleep(0.2)

    created = [delta for status, delta in results if status == "created" and delta is not None]
    print("\nSummary:")
    print(f"  attempts: {len(results)}")
    print(f"  statuses: {', '.join(status for status, _ in results)}")
    if created:
        print(f"  init_to_created min/median/max: {min(created):.3f}/{sorted(created)[len(created)//2]:.3f}/{max(created):.3f}s")
    print(f"  diagnostics: {diag_root}")
    return 2 if any(status == "init-hang" for status, _ in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
