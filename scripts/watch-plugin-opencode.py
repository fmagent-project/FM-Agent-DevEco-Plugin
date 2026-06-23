#!/usr/bin/env python3
"""Watch a DevEco-launched FM-Agent run and capture OpenCode state.

This script does not launch or kill FM-Agent/OpenCode. It observes the user's
real plugin run and captures diagnostics when OpenCode appears stuck or still
active shortly after session creation.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import time
from datetime import datetime, timezone


DEFAULT_TARGET = "/Users/lianganran/codes/2_SJTU_code/FM-agent/ExampleCppApp"
SENSITIVE = re.compile(r"(?i)(API_KEY|TOKEN|SECRET|PASSWORD|KEY)=([^ \n]*)")


def timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def redact(text: str) -> str:
    return SENSITIVE.sub(r"\1=<redacted>", text)


def run_capture(argv: list[str], out: pathlib.Path, timeout: int = 25) -> None:
    try:
        proc = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
        out.write_text(proc.stdout, encoding="utf-8", errors="replace")
    except Exception as exc:  # noqa: BLE001 - diagnostics must not hide the original issue
        out.write_text(f"failed to run {' '.join(argv)}: {exc}\n", encoding="utf-8")


def ps_lines() -> list[str]:
    proc = subprocess.run(["ps", "auxww"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return proc.stdout.splitlines()


def opencode_pids(target: pathlib.Path) -> dict[int, str]:
    marker = str(target / "fm_agent/")
    result: dict[int, str] = {}
    for line in ps_lines():
        if "opencode" not in line or "--file" not in line or marker not in line or "watch-plugin-opencode.py" in line:
            continue
        parts = line.split(None, 10)
        if len(parts) < 11:
            continue
        try:
            pid = int(parts[1])
        except ValueError:
            continue
        result[pid] = parts[10]
    return result


def latest_payload_log(target: pathlib.Path, since: float) -> pathlib.Path | None:
    payload_dir = target / "fm_agent/trace/payloads"
    if not payload_dir.is_dir():
        return None
    candidates = []
    for path in payload_dir.glob("*_opencode.log"):
        try:
            stat = path.stat()
        except FileNotFoundError:
            continue
        if stat.st_mtime < since - 5:
            continue
        candidates.append((stat.st_mtime, path))
    if not candidates:
        return None
    return max(candidates)[1]


def log_state(log_path: pathlib.Path | None) -> tuple[bool, bool, str]:
    if log_path is None or not log_path.is_file():
        return False, False, ""
    text = log_path.read_text(encoding="utf-8", errors="replace")
    return "message=init" in text, "message=created" in text, text


def collect(pid: int, command: str, diag_root: pathlib.Path, reason: str, log_path: pathlib.Path | None) -> pathlib.Path:
    snap = diag_root / f"{timestamp()}_pid{pid}_{re.sub(r'[^a-zA-Z0-9_.-]+', '_', reason)[:60]}"
    snap.mkdir(parents=True, exist_ok=True)
    (snap / "reason.txt").write_text(reason + "\n", encoding="utf-8")
    (snap / "command.txt").write_text(command + "\n", encoding="utf-8")

    run_capture(["ps", "-p", str(pid), "-o", "pid,ppid,pgid,stat,etime,%cpu,%mem,command"], snap / "ps.txt")
    run_capture(["lsof", "-p", str(pid)], snap / "lsof.txt", timeout=30)

    env_proc = subprocess.run(["ps", "eww", "-p", str(pid)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (snap / "process-env.txt").write_text(redact(env_proc.stdout), encoding="utf-8", errors="replace")

    if log_path and log_path.is_file():
        shutil.copy2(log_path, snap / log_path.name)

    global_log = pathlib.Path.home() / ".local/share/opencode/log/opencode.log"
    if global_log.is_file():
        tail = "\n".join(global_log.read_text(encoding="utf-8", errors="replace").splitlines()[-500:]) + "\n"
        (snap / "opencode-global-tail.log").write_text(tail, encoding="utf-8")

    sample = shutil.which("sample")
    if sample:
        run_capture([sample, str(pid), "5", "1", "-file", str(snap / "opencode.sample")], snap / "sample-command.txt", timeout=18)
    else:
        (snap / "sample-command.txt").write_text("sample command not found\n", encoding="utf-8")
    return snap


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", default=DEFAULT_TARGET)
    parser.add_argument("--diag-dir", default="")
    parser.add_argument("--watch-seconds", type=float, default=900.0)
    parser.add_argument("--init-delay", type=float, default=10.0)
    parser.add_argument("--created-delay", type=float, default=10.0)
    parser.add_argument("--age-delay", type=float, default=20.0)
    args = parser.parse_args()

    target = pathlib.Path(args.target).expanduser().resolve()
    if not target.is_dir():
        raise SystemExit(f"target does not exist: {target}")
    diag_root = (
        pathlib.Path(args.diag_dir).expanduser().resolve()
        if args.diag_dir
        else pathlib.Path(tempfile.gettempdir()) / "fm-agent-plugin-watch" / timestamp()
    )
    diag_root.mkdir(parents=True, exist_ok=True)

    start_wall = time.time()
    start = time.monotonic()
    tracked: dict[int, dict[str, object]] = {}

    print(f"Watching target: {target}", flush=True)
    print(f"Diagnostics: {diag_root}", flush=True)
    print("Waiting for plugin-launched OpenCode process...", flush=True)

    while time.monotonic() - start < args.watch_seconds:
        now = time.monotonic()
        current = opencode_pids(target)
        log_path = latest_payload_log(target, start_wall)
        saw_init, saw_created, _ = log_state(log_path)

        for pid, command in current.items():
            entry = tracked.setdefault(pid, {
                "first_seen": now,
                "command": command,
                "init_seen_at": None,
                "created_seen_at": None,
                "captured": set(),
            })
            entry["command"] = command
            captured: set[str] = entry["captured"]  # type: ignore[assignment]

            if saw_init and entry["init_seen_at"] is None:
                entry["init_seen_at"] = now
                print(f"pid {pid}: saw init in {log_path}", flush=True)
            if saw_created and entry["created_seen_at"] is None:
                entry["created_seen_at"] = now
                print(f"pid {pid}: saw created in {log_path}", flush=True)

            first_seen = float(entry["first_seen"])
            init_seen_at = entry["init_seen_at"]
            created_seen_at = entry["created_seen_at"]

            if (
                init_seen_at is not None
                and created_seen_at is None
                and "init-no-created" not in captured
                and now - float(init_seen_at) >= args.init_delay
            ):
                snap = collect(pid, command, diag_root, "init-no-created", log_path)
                captured.add("init-no-created")
                print(f"CAPTURED init-no-created: {snap}", flush=True)

            if (
                created_seen_at is not None
                and "post-created" not in captured
                and now - float(created_seen_at) >= args.created_delay
            ):
                snap = collect(pid, command, diag_root, "post-created-10s", log_path)
                captured.add("post-created")
                print(f"CAPTURED post-created-10s: {snap}", flush=True)

            if "age" not in captured and now - first_seen >= args.age_delay:
                snap = collect(pid, command, diag_root, "process-age-20s", log_path)
                captured.add("age")
                print(f"CAPTURED process-age-20s: {snap}", flush=True)

        for pid in list(tracked):
            if pid not in current:
                print(f"pid {pid}: process exited", flush=True)
                tracked.pop(pid, None)

        time.sleep(0.5)

    print(f"watch timeout after {args.watch_seconds}s", flush=True)
    print(f"Diagnostics: {diag_root}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
