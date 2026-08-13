#!/usr/bin/env python3

import importlib.util
import json
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify_agent_streaming_provider.py")
SPEC = importlib.util.spec_from_file_location("verify_agent_streaming_provider", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def event(delta: dict, finish_reason=None) -> bytes:
    return ("data: " + json.dumps({
        "choices": [{"delta": delta, "finish_reason": finish_reason}],
    }) + "\n").encode()


class StreamingProviderContractTest(unittest.TestCase):
    def test_accepts_exclusive_text_and_tool_steps(self) -> None:
        text = MODULE.inspect_sse([event({"content": "안녕"}), b"data: [DONE]\n"])
        tool = MODULE.inspect_sse([event({"tool_calls": [{"index": 0}]}), b"data: [DONE]\n"])
        self.assertTrue(text["exclusive"])
        self.assertEqual(text["contentChunks"], 1)
        self.assertTrue(tool["exclusive"])
        self.assertEqual(tool["toolChunks"], 1)
        self.assertEqual(tool["toolCallCount"], 1)

    def test_rejects_content_mixed_with_tool_chunks_in_one_step(self) -> None:
        report = MODULE.inspect_sse([
            event({"content": "먼저 말할게요"}),
            event({"tool_calls": [{"index": 0, "function": {"name": "weather"}}]}),
        ])
        self.assertFalse(report["exclusive"])

    def test_counts_fragmented_parallel_tool_calls_by_index(self) -> None:
        report = MODULE.inspect_sse([
            event({"tool_calls": [{"index": 0, "function": {"name": "weather"}}]}),
            event({"tool_calls": [{"index": 0, "function": {"arguments": "{}"}}]}),
            event({"tool_calls": [{"index": 1, "function": {"name": "clock"}}]}),
        ])
        self.assertTrue(report["exclusive"])
        self.assertEqual(report["toolChunks"], 3)
        self.assertEqual(report["toolCallCount"], 2)

    def test_single_tool_continuation_forces_a_text_only_final_step(self) -> None:
        payload = MODULE.final_after_one_tool_payload({"model": "fixture", "stream": True})
        self.assertEqual(payload["tool_choice"], "none")
        self.assertEqual(payload["messages"][-1]["role"], "tool")
        self.assertEqual(
            payload["messages"][1]["tool_calls"][0]["id"],
            payload["messages"][-1]["tool_call_id"],
        )

    def test_parallel_tool_continuation_preserves_both_call_identities(self) -> None:
        payload = MODULE.final_after_parallel_tools_payload({"model": "fixture", "stream": True})
        calls = {call["id"] for call in payload["messages"][1]["tool_calls"]}
        results = {message["tool_call_id"] for message in payload["messages"] if message["role"] == "tool"}
        self.assertEqual(calls, results)
        self.assertEqual(2, len(calls))


if __name__ == "__main__":
    unittest.main()
