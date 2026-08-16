package com.surenjanath.crownfoundry.api

/** Response bodies copied from ARCHITECTURE.md §5, so a contract drift breaks a test here first. */
object Fixtures {

    const val OPENING_FEN =
        "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12"

    val HEALTH = """
        {"ok": true, "version": "1.0.0", "ollama": {"available": true, "model": "qwen3.5:9b"},
         "policy_version": 12}
    """.trimIndent()

    val MATCH_START = """
        {"ok": true,
         "match_id": "3f2b1c0a-0000-4000-8000-000000000001",
         "initial_board": "$OPENING_FEN",
         "board": {"fen": "$OPENING_FEN", "side_to_move": "black",
                   "pieces": [{"square": 1, "side": "black", "king": false},
                              {"square": 32, "side": "white", "king": true}]},
         "legal_moves": [{"notation": "11-15", "from": 11, "to": 15, "captures": []},
                         {"notation": "11x18x25", "from": 11, "to": 25, "captures": [15, 22]}],
         "turn_number": 0,
         "ai": {"policy_version": 12, "games_trained": 340, "win_rate": 0.46, "elo": 1180}}
    """.trimIndent()

    val MATCH_DETAIL = """
        {"ok": true,
         "match_id": "3f2b1c0a-0000-4000-8000-000000000001",
         "board": {"fen": "$OPENING_FEN", "side_to_move": "white", "pieces": []},
         "legal_moves": [],
         "turn_number": 3,
         "status": "finished",
         "winner": "black",
         "history": [{"turn": 1, "side": "black", "move": "11-15", "fen": "$OPENING_FEN",
                      "reasoning": null},
                     {"turn": 2, "side": "white", "move": "24-19", "fen": "$OPENING_FEN",
                      "reasoning": "Trading into the centre."}]}
    """.trimIndent()

    val MOVE_RESULT = """
        {"ok": true, "valid": true, "game_over": false, "winner": null,
         "board_state": "W:W21,22:B1,2",
         "board": {"fen": "W:W21,22:B1,2", "side_to_move": "white", "pieces": []},
         "legal_moves": [{"notation": "23-18", "from": 23, "to": 18, "captures": []}],
         "applied_move": {"notation": "11-15", "captures": [], "crowned": false},
         "turn_number": 1}
    """.trimIndent()

    val AI_TURN = """
        {"ok": true,
         "ai_move": "24-19",
         "ai_reasoning": "Holding the centre so your right flank has nothing to trade into.",
         "reasoning_source": "ollama",
         "new_board": "B:W21,19:B1,2",
         "board": {"fen": "B:W21,19:B1,2", "side_to_move": "black", "pieces": []},
         "legal_moves": [{"notation": "11-15", "from": 11, "to": 15, "captures": []}],
         "evaluation": {"q_value": 0.41, "confidence": 0.78,
                        "considered": [{"notation": "24-19", "q": 0.41},
                                       {"notation": "23-18", "q": 0.36}]},
         "game_over": false, "winner": null, "turn_number": 2,
         "captures": [], "crowned": false}
    """.trimIndent()

    val MATCH_LIST = """
        {"ok": true,
         "matches": [{"match_id": "3f2b1c0a-0000-4000-8000-000000000001",
                      "start_time": "2026-08-16T11:00:00Z", "end_time": null,
                      "status": "active", "winner": null, "total_turns": 12,
                      "difficulty": "adaptive", "ai_captures": 2, "human_captures": 3}]}
    """.trimIndent()

    val RESIGN = """{"ok": true, "game_over": true, "winner": "white"}"""

    val SUMMARY = """
        {"total_matches": 41, "ai_wins": 19, "human_wins": 20, "draws": 2,
         "ai_win_rate": 0.463, "elo": 1180, "policy_version": 12,
         "games_to_50_percent": null, "avg_turns": 38.2,
         "mistake_repetition_rate": 0.07, "capture_ratio": 1.12}
    """.trimIndent()

    val PERFORMANCE = """
        {"ok": true,
         "summary": $SUMMARY,
         "win_rate_series": [{"match_index": 1, "cumulative_win_rate": 0.0,
                              "rolling_win_rate": 0.0, "result": "loss"}],
         "game_length_series": [{"match_index": 1, "turns": 44}],
         "mistake_series": [{"match_index": 1, "repeated_mistakes": 2, "rate": 0.09}],
         "capture_series": [{"match_index": 1, "ai_captures": 4, "human_captures": 7}],
         "training": [{"policy_version": 3, "loss": 0.11, "games_trained": 120,
                       "updated_at": "2026-08-16T11:00:00Z"}]}
    """.trimIndent()

    val ILLEGAL_MOVE = """
        {"ok": false, "valid": false, "error": "illegal_move",
         "detail": "Captures are mandatory.",
         "legal_moves": [{"notation": "11x18", "from": 11, "to": 18, "captures": [15]},
                         {"notation": "12x19", "from": 12, "to": 19, "captures": [16]}]}
    """.trimIndent()
}
