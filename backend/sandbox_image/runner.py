"""Skill sandbox runner (see docs/features/LEARNING.md §1 Sandbox).

Reads JSON from stdin: {"code": str, "entry": str, "cases": [input, ...]}.
Each case runs in a fresh, isolated interpreter (`python -I`) with a wall-clock timeout, so module-level
state can't leak between cases. Writes JSON to stdout: [{"ok": true, "value": ...} | {"ok": false, "error": "..."}].
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile

CASE_TIMEOUT_S = float(os.environ.get("CASE_TIMEOUT_S", "10"))
MAX_OUTPUT = 64 * 1024

# Runs inside the per-case child: load the skill, call the entry function, print one JSON line.
CHILD = r"""
import json, sys
src = sys.stdin.buffer.read().decode("utf-8")  # not the locale: Hindi skills on Windows
job = json.loads(src)
ns = {"__name__": "skill"}
try:
    exec(compile(job["code"], "skill.py", "exec"), ns)
    fn = ns[job["entry"]]
    value = fn(job["input"])
    out = {"ok": True, "value": value}
except BaseException as exc:  # noqa: BLE001 - report every failure as data
    out = {"ok": False, "error": f"{type(exc).__name__}: {exc}"[:500]}
sys.stdout.write("\n__RESULT__" + json.dumps(out, default=str))
"""


def run_case(code: str, entry: str, case: object) -> dict:
    job = json.dumps({"code": code, "entry": entry, "input": case})  # ASCII: safe in any locale
    try:
        proc = subprocess.run(
            [sys.executable, "-I", "-S", "-c", CHILD],
            input=job,
            capture_output=True,
            text=True,
            timeout=CASE_TIMEOUT_S,
            cwd=tempfile.gettempdir(),
            env={"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8", "PYTHONDONTWRITEBYTECODE": "1"},
        )
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": f"Timeout: case exceeded {CASE_TIMEOUT_S:.0f}s"}
    out = proc.stdout[-MAX_OUTPUT:]
    marker = out.rfind("\n__RESULT__")
    if marker < 0:
        err = (proc.stderr or "no output").strip().splitlines()[-1:] or ["no output"]
        return {"ok": False, "error": f"Crashed (exit {proc.returncode}): {err[0][:300]}"}
    try:
        return json.loads(out[marker + len("\n__RESULT__") :])
    except json.JSONDecodeError:
        return {"ok": False, "error": "Unreadable result"}


def main() -> int:
    try:
        job = json.loads(sys.stdin.buffer.read().decode("utf-8"))
        code, entry, cases = job["code"], job["entry"], job["cases"]
    except (json.JSONDecodeError, KeyError, TypeError) as exc:
        json.dump([{"ok": False, "error": f"Bad job: {exc}"}], sys.stdout)
        return 2
    json.dump([run_case(code, entry, c) for c in cases], sys.stdout, default=str)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
