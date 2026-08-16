"""The Ollama bridge, with ``requests`` monkeypatched.

Nothing here talks to a real server: Ollama is optional and the test suite must pass on a machine
that has never heard of it.
"""

from __future__ import annotations

import json
from unittest import mock

import requests
from django.test import SimpleTestCase

from ai import ollama
from ai.service import ScoredMove
from game.engine.board import Board
from game.engine.notation import WHITE

from . import FEN_FORCED_JUMP, FEN_QUIET, cf


class FakeResponse:
    def __init__(self, payload, status: int = 200, text: str | None = None):
        self._payload = payload
        self.status_code = status
        self.text = text if text is not None else json.dumps(payload)

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"status {self.status_code}")

    def json(self):
        if self._payload is _BAD_JSON:
            raise ValueError("not json")
        return self._payload


_BAD_JSON = object()


def chat_reply(content: str) -> FakeResponse:
    return FakeResponse({"message": {"role": "assistant", "content": content}})


class BridgeTestCase(SimpleTestCase):
    def setUp(self):
        ollama.clear_status_cache()
        self.addCleanup(ollama.clear_status_cache)
        self.board = Board.from_fen(FEN_QUIET)
        self.moves = self.board.legal_moves()
        self.index = {m.notation(): m for m in self.moves}
        self.candidates = [
            ScoredMove("23-18", 0.71),
            ScoredMove("22-17", 0.44),
            ScoredMove("21-17", 0.12),
        ]
        self.best = self.index["23-18"]


class HappyPathTests(BridgeTestCase):
    def test_parses_the_choice_and_the_reason(self):
        payload = json.dumps({"move": "22-17", "reason": "Keeping the centre column guarded."})
        with mock.patch.object(ollama.requests, "post", return_value=chat_reply(payload)) as post:
            result = ollama.narrate(self.board, self.best, self.candidates, self.index,
                                    side=WHITE)
        self.assertEqual(result.notation, "22-17")
        self.assertEqual(result.reasoning, "Keeping the centre column guarded.")
        self.assertEqual(result.source, ollama.SOURCE_OLLAMA)

        body = post.call_args.kwargs["json"]
        self.assertEqual(body["model"], "qwen3.5:9b")
        self.assertFalse(body["stream"])
        prompt = body["messages"][-1]["content"]
        for candidate in self.candidates:
            self.assertIn(candidate.notation, prompt)
        self.assertIn(self.board.to_fen(), prompt)

    def test_accepts_the_generate_endpoint_shape(self):
        payload = FakeResponse({"response": '{"move": "21-17", "reason": "Edge is safe."}'})
        with mock.patch.object(ollama.requests, "post", return_value=payload):
            result = ollama.narrate(self.board, self.best, self.candidates, self.index)
        self.assertEqual(result.notation, "21-17")
        self.assertEqual(result.source, ollama.SOURCE_OLLAMA)

    def test_unwraps_fenced_json_and_surrounding_prose(self):
        content = 'Sure!\n```json\n{"move": "22-18", "reason": "Trade into the open."}\n```\nDone.'
        with mock.patch.object(ollama.requests, "post", return_value=chat_reply(content)):
            result = ollama.narrate(self.board, self.best,
                                    self.candidates + [ScoredMove("22-18", 0.3)], self.index)
        self.assertEqual(result.notation, "22-18")
        self.assertEqual(result.reasoning, "Trade into the open.")

    def test_tolerates_whitespace_and_alternate_keys(self):
        content = json.dumps({"notation": " 22 - 17 ", "reasoning": "Solid.\n  Very solid."})
        with mock.patch.object(ollama.requests, "post", return_value=chat_reply(content)):
            result = ollama.narrate(self.board, self.best, self.candidates, self.index)
        self.assertEqual(result.notation, "22-17")
        self.assertEqual(result.reasoning, "Solid. Very solid.")

    def test_a_long_reason_is_truncated(self):
        content = json.dumps({"move": "23-18", "reason": "x" * 900})
        with mock.patch.object(ollama.requests, "post", return_value=chat_reply(content)):
            result = ollama.narrate(self.board, self.best, self.candidates, self.index)
        self.assertLessEqual(len(result.reasoning), 400)
        self.assertTrue(result.reasoning.endswith("..."))

    def test_an_empty_reason_falls_back_to_the_narrator_but_keeps_the_choice(self):
        content = json.dumps({"move": "22-17", "reason": "   "})
        with mock.patch.object(ollama.requests, "post", return_value=chat_reply(content)):
            result = ollama.narrate(self.board, self.best, self.candidates, self.index)
        self.assertEqual(result.notation, "22-17")
        self.assertEqual(result.source, ollama.SOURCE_OLLAMA)
        self.assertIn("22-17", result.reasoning)


class FallbackTests(BridgeTestCase):
    def _assert_fell_back(self, result):
        self.assertEqual(result.notation, self.best.notation())
        self.assertEqual(result.source, ollama.SOURCE_HEURISTIC)
        self.assertTrue(result.reasoning.strip())

    def test_a_move_outside_the_candidate_list_falls_back_to_the_rl_top_move(self):
        # 9-14 is a legal move for Black, and not on the shortlist the engine offered.
        content = json.dumps({"move": "9-14", "reason": "I like this one."})
        with mock.patch.object(ollama.requests, "post", return_value=chat_reply(content)):
            result = ollama.narrate(self.board, self.best, self.candidates, self.index)
        self._assert_fell_back(result)
        self.assertNotIn("I like this one", result.reasoning)

    def test_an_invented_move_falls_back(self):
        content = json.dumps({"move": "99-100", "reason": "Trust me."})
        with mock.patch.object(ollama.requests, "post", return_value=chat_reply(content)):
            self._assert_fell_back(
                ollama.narrate(self.board, self.best, self.candidates, self.index)
            )

    def test_timeout_falls_back_to_the_heuristic_narrator(self):
        with mock.patch.object(ollama.requests, "post",
                               side_effect=requests.Timeout("too slow")):
            self._assert_fell_back(
                ollama.narrate(self.board, self.best, self.candidates, self.index)
            )

    def test_connection_error_falls_back(self):
        with mock.patch.object(ollama.requests, "post",
                               side_effect=requests.ConnectionError("refused")):
            self._assert_fell_back(
                ollama.narrate(self.board, self.best, self.candidates, self.index)
            )

    def test_model_not_pulled_falls_back(self):
        with mock.patch.object(ollama.requests, "post",
                               return_value=FakeResponse({"error": "model not found"}, 404)):
            self._assert_fell_back(
                ollama.narrate(self.board, self.best, self.candidates, self.index)
            )

    def test_garbage_json_falls_back(self):
        with mock.patch.object(ollama.requests, "post", return_value=chat_reply("¯\\_(ツ)_/¯")):
            self._assert_fell_back(
                ollama.narrate(self.board, self.best, self.candidates, self.index)
            )

    def test_unparseable_http_body_falls_back(self):
        body = FakeResponse(_BAD_JSON, text="<html>nginx</html>")
        with mock.patch.object(ollama.requests, "post", return_value=body):
            self._assert_fell_back(
                ollama.narrate(self.board, self.best, self.candidates, self.index)
            )

    def test_disabled_in_settings_never_calls_out(self):
        with cf(OLLAMA_ENABLED=False):
            with mock.patch.object(ollama.requests, "post") as post:
                result = ollama.narrate(self.board, self.best, self.candidates, self.index)
        post.assert_not_called()
        self._assert_fell_back(result)

    def test_no_candidates_never_calls_out(self):
        with mock.patch.object(ollama.requests, "post") as post:
            result = ollama.narrate(self.board, self.best, [], self.index)
        post.assert_not_called()
        self.assertEqual(result.source, ollama.SOURCE_HEURISTIC)

    def test_the_configured_timeout_is_passed_through(self):
        with cf(OLLAMA_TIMEOUT=1.5):
            with mock.patch.object(ollama.requests, "post",
                                   return_value=chat_reply("{}")) as post:
                ollama.narrate(self.board, self.best, self.candidates, self.index)
        self.assertEqual(post.call_args.kwargs["timeout"], 1.5)


class HeuristicNarratorTests(SimpleTestCase):
    def test_a_capture_is_described_as_one(self):
        board = Board.from_fen(FEN_FORCED_JUMP)
        move = board.legal_moves()[0]  # 18x11x2: two captures, crowning at the end
        sentence = ollama.heuristic_reason(board, move)
        self.assertIn("18x11x2", sentence)
        self.assertIn("jump", sentence)
        self.assertIn("crowning", sentence)
        self.assertTrue(sentence.endswith("."))

    def test_a_quiet_move_still_says_something_true(self):
        board = Board.from_fen(FEN_QUIET)
        move = board.parse_move("23-18")
        sentence = ollama.heuristic_reason(board, move)
        self.assertIn("23-18", sentence)
        self.assertGreater(len(sentence.split()), 4)
        self.assertTrue(sentence.endswith("."))

    def test_it_notices_giving_up_a_back_rank_guard(self):
        board = Board.from_fen("W:W29,30,18:B5,6")
        sentence = ollama.heuristic_reason(board, board.parse_move("29-25"))
        self.assertIn("back-rank", sentence)

    def test_every_legal_move_in_a_position_gets_a_sentence(self):
        board = Board.from_fen(FEN_QUIET)
        for move in board.legal_moves():
            sentence = ollama.heuristic_reason(board, move)
            self.assertTrue(sentence.strip())
            self.assertIn(move.notation(), sentence)

    def test_board_rendering_shows_pieces_and_empty_square_numbers(self):
        rendered = ollama.render_board(Board.initial())
        self.assertEqual(len(rendered.splitlines()), 8)
        self.assertIn("b", rendered)
        self.assertIn("w", rendered)
        self.assertIn("13", rendered)  # an empty playable square in the middle


class StatusTests(SimpleTestCase):
    def setUp(self):
        ollama.clear_status_cache()
        self.addCleanup(ollama.clear_status_cache)

    def _tags(self, *names):
        return FakeResponse({"models": [{"name": n} for n in names]})

    def test_available_when_the_model_is_listed(self):
        with mock.patch.object(ollama.requests, "get",
                               return_value=self._tags("qwen3.5:9b", "ornith:9b")):
            self.assertEqual(ollama.status(), {"available": True, "model": "qwen3.5:9b"})

    def test_available_when_only_the_base_name_matches(self):
        with mock.patch.object(ollama.requests, "get", return_value=self._tags("qwen3.5:latest")):
            self.assertTrue(ollama.status()["available"])

    def test_unavailable_when_the_model_was_never_pulled(self):
        with mock.patch.object(ollama.requests, "get", return_value=self._tags("llama3:8b")):
            self.assertFalse(ollama.status()["available"])

    def test_connection_refused_reports_unavailable_without_raising(self):
        with mock.patch.object(ollama.requests, "get",
                               side_effect=requests.ConnectionError("refused")):
            self.assertEqual(ollama.status(), {"available": False, "model": "qwen3.5:9b"})

    def test_status_never_raises_whatever_goes_wrong(self):
        for boom in (requests.Timeout("slow"), ValueError("garbage"), OSError("socket"),
                     RuntimeError("?")):
            ollama.clear_status_cache()
            with mock.patch.object(ollama.requests, "get", side_effect=boom):
                result = ollama.status()
            self.assertIs(result["available"], False)
            self.assertEqual(result["model"], "qwen3.5:9b")

    def test_a_garbled_tag_list_reports_unavailable(self):
        with mock.patch.object(ollama.requests, "get",
                               return_value=FakeResponse({"models": "not a list"})):
            self.assertFalse(ollama.status()["available"])

    def test_the_result_is_cached(self):
        with mock.patch.object(ollama.requests, "get",
                               return_value=self._tags("qwen3.5:9b")) as get:
            ollama.status()
            ollama.status()
            ollama.status()
        self.assertEqual(get.call_count, 1)

    def test_force_bypasses_the_cache(self):
        with mock.patch.object(ollama.requests, "get",
                               return_value=self._tags("qwen3.5:9b")) as get:
            ollama.status()
            ollama.status(force=True)
        self.assertEqual(get.call_count, 2)

    def test_the_probe_timeout_is_capped_so_it_cannot_block_a_request(self):
        with cf(OLLAMA_TIMEOUT=120.0):
            with mock.patch.object(ollama.requests, "get",
                                   return_value=self._tags("qwen3.5:9b")) as get:
                ollama.status()
        self.assertLessEqual(get.call_args.kwargs["timeout"], 3.0)

    def test_disabled_reports_unavailable_without_probing(self):
        with cf(OLLAMA_ENABLED=False):
            with mock.patch.object(ollama.requests, "get") as get:
                self.assertEqual(ollama.status(), {"available": False, "model": "qwen3.5:9b"})
        get.assert_not_called()

    def test_the_model_name_comes_from_settings(self):
        with cf(OLLAMA_MODEL="ornith:9b"):
            with mock.patch.object(ollama.requests, "get", return_value=self._tags("ornith:9b")):
                self.assertEqual(ollama.status(), {"available": True, "model": "ornith:9b"})
