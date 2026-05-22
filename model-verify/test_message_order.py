#!/usr/bin/env python3
"""Reproduce SenseNova failure: user message before system + tools."""
import json
import re
import urllib.request
from pathlib import Path

props = Path(__file__).resolve().parent.parent / "shell/src/main/resources/application.properties"
key = re.search(r"^spring\.ai\.openai\.api-key=(.+)$", props.read_text(encoding="utf-8"), re.M).group(1).strip()
url = "https://token.sensenova.cn/v1/chat/completions"
model = "sensenova-6.7-flash-lite"

tools = [
    {
        "type": "function",
        "function": {
            "name": "create_chat_completion",
            "description": "Creates a structured completion with specified output formatting.",
            "parameters": {
                "type": "object",
                "properties": {
                    "response": {
                        "type": "string",
                        "description": "The response text that should be delivered to the user.",
                    }
                },
                "required": ["response"],
                "strict": True,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "terminate",
            "description": "Terminate the interaction when the request is met.",
            "parameters": {
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "description": "The finish status of the interaction.",
                    }
                },
                "required": ["status"],
                "strict": True,
            },
        },
    },
]


def call(label: str, messages: list) -> None:
    payload = {"model": model, "messages": messages, "tools": tools, "tool_choice": "auto"}
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read().decode())
            msg = data["choices"][0]["message"]
            print(label, "OK", "tool_calls=", bool(msg.get("tool_calls")))
    except urllib.error.HTTPError as exc:
        print(label, "FAIL", exc.code, exc.read().decode()[:160])


call(
    "wrong-order",
    [
        {"role": "user", "content": "你好"},
        {"role": "system", "content": "You are an agent that can execute tool calls"},
        {
            "role": "user",
            "content": "If you want to stop interaction, use `terminate` tool/function call.",
        },
    ],
)
call(
    "fixed-order",
    [
        {"role": "system", "content": "You are an agent that can execute tool calls"},
        {"role": "user", "content": "你好"},
        {
            "role": "user",
            "content": "If you want to stop interaction, use `terminate` tool/function call.",
        },
    ],
)
