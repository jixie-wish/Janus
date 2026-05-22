#!/usr/bin/env python3
"""
Verify SenseNova chat model: sensenova-6.7-flash-lite

Usage:
  python sensenova-6.7-flash-lite.py
  python sensenova-6.7-flash-lite.py --prompt "你好"
  set SENSENOVA_API_KEY=sk-xxx && python sensenova-6.7-flash-lite.py
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

MODEL = "sensenova-6.7-flash-lite"
BASE_URL = "https://token.sensenova.cn/v1"
DEFAULT_PROMPT = "你好"

_REPO_ROOT = Path(__file__).resolve().parent.parent
_APP_PROPERTIES = _REPO_ROOT / "shell" / "src" / "main" / "resources" / "application.properties"


def load_api_key() -> str | None:
    key = os.environ.get("SENSENOVA_API_KEY") or os.environ.get("OPENAI_API_KEY")
    if key:
        return key.strip()

    if _APP_PROPERTIES.is_file():
        text = _APP_PROPERTIES.read_text(encoding="utf-8")
        match = re.search(r"^spring\.ai\.openai\.api-key=(.+)$", text, re.MULTILINE)
        if match:
            return match.group(1).strip()

    return None


def chat_completion(
    api_key: str, prompt: str, timeout: float, *, spring_agent_order: bool = False
) -> dict:
    url = f"{BASE_URL.rstrip('/')}/chat/completions"
    if spring_agent_order:
        # Mirrors Janus ToolCallAgent after BaseAgent fix (system must be first)
        messages = [
            {"role": "system", "content": "You are an agent that can execute tool calls"},
            {"role": "user", "content": prompt},
            {
                "role": "user",
                "content": "If you want to stop interaction, use `terminate` tool/function call.",
            },
        ]
    else:
        messages = [{"role": "user", "content": prompt}]

    payload = {"model": MODEL, "messages": messages}
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def extract_reply(data: dict) -> str:
    choices = data.get("choices") or []
    if not choices:
        return "(empty choices)"
    message = choices[0].get("message") or {}
    return message.get("content") or "(no content)"


def main() -> int:
    if sys.platform == "win32":
        for stream in (sys.stdout, sys.stderr):
            if hasattr(stream, "reconfigure"):
                stream.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(description=f"Verify model {MODEL}")
    parser.add_argument("-p", "--prompt", default=DEFAULT_PROMPT, help="Test prompt")
    parser.add_argument("--timeout", type=float, default=60.0, help="HTTP timeout (seconds)")
    parser.add_argument("--key", help="API key (overrides env / application.properties)")
    parser.add_argument(
        "--spring-agent-order",
        action="store_true",
        help="Use system-then-user message order (same as fixed Janus agent)",
    )
    args = parser.parse_args()

    api_key = (args.key or load_api_key() or "").strip()
    if not api_key:
        print(
            "Missing API key. Set SENSENOVA_API_KEY or pass --key, "
            f"or configure spring.ai.openai.api-key in {_APP_PROPERTIES}",
            file=sys.stderr,
        )
        return 1

    print(f"Model:   {MODEL}")
    print(f"Base:    {BASE_URL}")
    print(f"Prompt:  {args.prompt!r}")
    print("-" * 40)

    try:
        data = chat_completion(
            api_key, args.prompt, args.timeout, spring_agent_order=args.spring_agent_order
        )
    except urllib.error.HTTPError as exc:
        err_body = exc.read().decode("utf-8", errors="replace")
        print(f"HTTP {exc.code}: {exc.reason}", file=sys.stderr)
        try:
            print(json.dumps(json.loads(err_body), ensure_ascii=False, indent=2), file=sys.stderr)
        except json.JSONDecodeError:
            print(err_body, file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"Request failed: {exc.reason}", file=sys.stderr)
        return 1

    print("OK")
    print(f"Reply: {extract_reply(data)}")
    usage = data.get("usage")
    if usage:
        print(f"Usage: {usage}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
