#!/usr/bin/env python3
"""Probe an OpenAI-compatible model's tool/text streaming exclusivity contract."""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.request


TOOL = {
    "type": "function",
    "function": {
        "name": "contract_probe_weather",
        "description": "Return weather for a city. This is a contract probe.",
        "parameters": {
            "type": "object",
            "properties": {"city": {"type": "string"}},
            "required": ["city"],
            "additionalProperties": False,
        },
    },
}
SECOND_TOOL = {
    "type": "function",
    "function": {
        "name": "contract_probe_clock",
        "description": "Return local time for a city. This is a contract probe.",
        "parameters": {
            "type": "object",
            "properties": {"city": {"type": "string"}},
            "required": ["city"],
            "additionalProperties": False,
        },
    },
}


def final_after_one_tool_payload(common: dict) -> dict:
    return {
        **common,
        "messages": [
            {"role": "user", "content": "Use the weather tool for Seoul, then answer briefly."},
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": "contract_weather_1",
                    "type": "function",
                    "function": {"name": "contract_probe_weather", "arguments": '{"city":"Seoul"}'},
                }],
            },
            {
                "role": "tool",
                "tool_call_id": "contract_weather_1",
                "content": '{"city":"Seoul","condition":"clear","temperatureC":24}',
            },
        ],
        "tools": [TOOL],
        "tool_choice": "none",
    }


def final_after_parallel_tools_payload(common: dict) -> dict:
    return {
        **common,
        "messages": [
            {"role": "user", "content": "Use weather and clock for Seoul, then answer briefly."},
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "contract_weather_2",
                        "type": "function",
                        "function": {"name": "contract_probe_weather", "arguments": '{"city":"Seoul"}'},
                    },
                    {
                        "id": "contract_clock_2",
                        "type": "function",
                        "function": {"name": "contract_probe_clock", "arguments": '{"city":"Seoul"}'},
                    },
                ],
            },
            {
                "role": "tool",
                "tool_call_id": "contract_weather_2",
                "content": '{"city":"Seoul","condition":"clear","temperatureC":24}',
            },
            {
                "role": "tool",
                "tool_call_id": "contract_clock_2",
                "content": '{"city":"Seoul","localTime":"15:30"}',
            },
        ],
        "tools": [TOOL, SECOND_TOOL],
        "tool_choice": "none",
    }


def inspect_sse(lines: list[bytes]) -> dict:
    content_chunks = 0
    tool_chunks = 0
    tool_call_indexes = set()
    finish_reasons = []
    for raw in lines:
        line = raw.decode("utf-8").strip()
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            continue
        payload = json.loads(data)
        for choice in payload.get("choices", []):
            delta = choice.get("delta") or {}
            if isinstance(delta.get("content"), str) and delta["content"]:
                content_chunks += 1
            if delta.get("tool_calls"):
                tool_chunks += 1
                for call in delta["tool_calls"]:
                    index = call.get("index")
                    if isinstance(index, int) and index >= 0:
                        tool_call_indexes.add(index)
            if choice.get("finish_reason") is not None:
                finish_reasons.append(choice["finish_reason"])
    return {
        "contentChunks": content_chunks,
        "toolChunks": tool_chunks,
        "toolCallCount": len(tool_call_indexes),
        "finishReasons": finish_reasons,
        "exclusive": not (content_chunks and tool_chunks),
    }


def request_stream(url: str, api_key: str, payload: dict, timeout: float) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "authorization": f"Bearer {api_key}",
            "content-type": "application/json",
            "accept": "text/event-stream",
            "user-agent": "gahyeon-streaming-contract-probe/1",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return inspect_sse(list(response))
    except urllib.error.HTTPError as error:
        # Never print a provider body: it can echo request details or account data.
        raise RuntimeError(f"provider returned HTTP {error.code}") from error


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.getenv("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"))
    parser.add_argument("--model", default=os.getenv("OPENROUTER_MODEL", ""))
    parser.add_argument("--api-key-env", default="OPENROUTER_API_KEY")
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()
    api_key = os.getenv(args.api_key_env, "")
    if not args.model:
        raise SystemExit("--model or OPENROUTER_MODEL is required")
    if not api_key:
        raise SystemExit(f"{args.api_key_env} is required")
    endpoint = args.base_url.rstrip("/") + "/chat/completions"
    common = {"model": args.model, "stream": True, "temperature": 0, "max_tokens": 80}
    direct = request_stream(endpoint, api_key, {
        **common,
        "messages": [{"role": "user", "content": "Reply with exactly: CONTRACT_OK"}],
    }, args.timeout)
    forced_tool = request_stream(endpoint, api_key, {
        **common,
        "messages": [{"role": "user", "content": "Use the weather tool for Seoul."}],
        "tools": [TOOL],
        "tool_choice": {"type": "function", "function": {"name": "contract_probe_weather"}},
    }, args.timeout)
    parallel_tools = request_stream(endpoint, api_key, {
        **common,
        "messages": [{
            "role": "user",
            "content": "Call both available tools for Seoul in the same assistant step.",
        }],
        "tools": [TOOL, SECOND_TOOL],
        "tool_choice": "required",
        "parallel_tool_calls": True,
    }, args.timeout)
    final_after_tool = request_stream(
        endpoint, api_key, final_after_one_tool_payload(common), args.timeout)
    final_after_parallel = request_stream(
        endpoint, api_key, final_after_parallel_tools_payload(common), args.timeout)
    direct_ready = direct["exclusive"] and direct["contentChunks"] > 0 and direct["toolChunks"] == 0
    tool_ready = forced_tool["exclusive"] and forced_tool["toolChunks"] > 0 and forced_tool["contentChunks"] == 0
    parallel_ready = (parallel_tools["exclusive"]
                      and parallel_tools["toolCallCount"] >= 2
                      and parallel_tools["contentChunks"] == 0)
    final_after_tool_ready = (final_after_tool["exclusive"]
                              and final_after_tool["contentChunks"] > 0
                              and final_after_tool["toolChunks"] == 0)
    final_after_parallel_ready = (final_after_parallel["exclusive"]
                                  and final_after_parallel["contentChunks"] > 0
                                  and final_after_parallel["toolChunks"] == 0)
    report = {
        "model": args.model,
        "direct": direct,
        "forcedTool": forced_tool,
        "parallelTools": parallel_tools,
        "finalAfterTool": final_after_tool,
        "finalAfterParallelTools": final_after_parallel,
        "ready": (direct_ready and tool_ready and parallel_ready
                  and final_after_tool_ready and final_after_parallel_ready),
    }
    print(json.dumps(report, ensure_ascii=False))
    if not report["ready"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
