"""Runs compiled Python skills out of process (docs/features/LEARNING.md §1 Sandbox).

`SKILL_SANDBOX=docker` (the server): every job is a fresh `saathi-skill-sandbox` container with no network, a
read-only root, 128 MB, half a CPU, 32 pids, no capabilities, and an unprivileged user.
`SKILL_SANDBOX=subprocess` (development): the same runner in an isolated local interpreter, with no extra limits.
`SKILL_SANDBOX=off`: Python skills never run, and the LLM answers.

Before any of that, `static_check` rejects code that imports anything outside a small allowlist or touches
dunders, files, eval/exec or the interpreter, so most hostile code never reaches the sandbox at all.
"""

from __future__ import annotations

import ast
import asyncio
import contextlib
import json
import logging
import os
import sys
from pathlib import Path
from typing import Any

log = logging.getLogger("saathi.sandbox")

ALLOWED_IMPORTS = {
    "re",
    "json",
    "datetime",
    "math",
    "string",
    "unicodedata",
    "difflib",
    "collections",
    "itertools",
}
FORBIDDEN_NAMES = {
    "open", "exec", "eval", "compile", "__import__", "input", "breakpoint", "globals", "locals", "vars",
    "getattr", "setattr", "delattr", "memoryview", "help", "exit", "quit", "dir", "type", "object",
}  # fmt: skip
MAX_CODE_CHARS = 20_000
RUNNER = Path(__file__).resolve().parents[2] / "sandbox_image" / "runner.py"
DOCKER_FLAGS = [
    "--rm", "-i", "--network", "none", "--read-only", "--tmpfs", "/tmp:size=4m", "--memory", "128m",
    "--cpus", "0.5", "--pids-limit", "32", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
    "--user", "65534:65534",
]  # fmt: skip


def static_check(code: str, entry: str) -> list[str]:
    """Problems that disqualify the code before it is ever run (empty list = acceptable)."""
    if len(code) > MAX_CODE_CHARS:
        return ["code too long"]
    try:
        tree = ast.parse(code)
    except SyntaxError as exc:
        return [f"syntax error line {exc.lineno}: {exc.msg}"]
    problems: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name.split(".")[0] not in ALLOWED_IMPORTS:
                    problems.append(f"import of {alias.name} is not allowed")
        elif isinstance(node, ast.ImportFrom):
            if (node.module or "").split(".")[0] not in ALLOWED_IMPORTS or node.level:
                problems.append(f"import from {node.module} is not allowed")
        elif isinstance(node, ast.Name) and node.id in FORBIDDEN_NAMES:
            problems.append(f"use of {node.id} is not allowed")
        elif isinstance(node, ast.Attribute) and node.attr.startswith("__"):
            problems.append(f"dunder attribute {node.attr} is not allowed")
        elif isinstance(node, ast.Name) and node.id.startswith("__"):
            problems.append(f"dunder name {node.id} is not allowed")
        elif isinstance(node, (ast.Global, ast.Nonlocal, ast.AsyncFunctionDef, ast.Await, ast.Lambda)):
            problems.append(f"{type(node).__name__} is not allowed")
    if not any(isinstance(n, ast.FunctionDef) and n.name == entry for n in tree.body):
        problems.append(f"no top-level function {entry}()")
    return sorted(set(problems))


class Sandbox:
    def __init__(self, mode: str | None = None, image: str | None = None, timeout_s: float = 30.0) -> None:
        self.mode = (mode or os.environ.get("SKILL_SANDBOX", "subprocess")).strip().lower()
        self.image = image or os.environ.get("SKILL_SANDBOX_IMAGE", "saathi-skill-sandbox")
        self.timeout_s = timeout_s
        if self.mode == "subprocess" and not RUNNER.exists():
            self.mode = "off"

    @property
    def enabled(self) -> bool:
        return self.mode in {"docker", "subprocess"}

    def _command(self) -> list[str]:
        if self.mode == "docker":
            return ["docker", "run", *DOCKER_FLAGS, self.image]
        return [sys.executable, "-I", str(RUNNER)]

    async def run(self, code: str, entry: str, cases: list[Any]) -> list[dict[str, Any]]:
        """One result per case: {"ok": True, "value": …} or {"ok": False, "error": "…"}. Never raises."""
        if not self.enabled:
            return [{"ok": False, "error": "sandbox off"} for _ in cases]
        problems = static_check(code, entry)
        if problems:
            return [{"ok": False, "error": "static check: " + "; ".join(problems[:3])} for _ in cases]
        job = json.dumps({"code": code, "entry": entry, "cases": cases}, ensure_ascii=False).encode()
        try:
            proc = await asyncio.create_subprocess_exec(
                *self._command(),
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            out, _ = await asyncio.wait_for(proc.communicate(job), self.timeout_s)
            results = json.loads(out.decode("utf-8", "replace") or "[]")
        except TimeoutError:
            with contextlib.suppress(ProcessLookupError):
                proc.kill()
            return [{"ok": False, "error": "sandbox timeout"} for _ in cases]
        except Exception as exc:  # noqa: BLE001 - docker missing, bad output, …
            log.warning("sandbox run failed: %s", type(exc).__name__)
            return [{"ok": False, "error": f"sandbox error: {type(exc).__name__}"} for _ in cases]
        if not isinstance(results, list) or len(results) != len(cases):
            return [{"ok": False, "error": "sandbox returned the wrong number of results"} for _ in cases]
        return results
