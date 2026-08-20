# Stronger opponent — design

Date: 2026-08-18
Status: approved
Scope: Django `ai` + analytics dashboard + Kotlin `:engine` / offline play. CFE1 layout unchanged.

## Problem

The trained policy is still in SQLite (v27, 679 games). Live play does not use the opening book. Adaptive throws away 6–20% of moves at random. Ollama may replace the engine’s move. The dashboard clamps training to 1,000 games. The phone downloads CFE1 weights on sync, so a stronger saved policy does reach the device — but the book and the live-play leaks do not travel inside that file.

## Goals

- Live play (server and phone) follows the opening book when the line is legal.
- Adaptive/Hard play to win: no random moves, Ollama narrates only.
- Live search depth 6. Training stays depth 2 for volume.
- Dashboard/API allow 25,000-game jobs.
- After the current job, run 25,000 curriculum games (book on, evaluate off) so the next CFE1 the phone syncs is the long-trained policy.

## Non-goals

- No CFE1 / `FEATURE_SIZE` / `HIDDEN_LAYERS` / `encode()` change.
- No MCTS. No larger network. No Celery.
- Easy and Normal keep their handicaps.
- Book is not stored in the `.cfe` file.

## Architecture

```
ai/opening_book.py     book_move(board, history) -> Move | None
ai/agent.py            knobs_for: adaptive/hard epsilon=0; deeper if human winning
ai/service.py          ai_turn: book first, select(explore=False), no Ollama override
ai/training.py         clamp games to 25_000
ai/conf.py             SEARCH_DEPTH default 6
analytics/dashboard    max 25000, presets 1k / 5k / 25k
Mobile/.../OpeningBook.kt   same 16 lines, same lookup
Mobile/.../Agent.kt         knobsFor + select(history)
OfflineCheckersApi          pass match.moves, explore=false
```

## 1. Training cap

`clamp_games(n) -> max(5, min(n, 25000))`. `start_training` uses it. CLI `train_selfplay` stays uncapped. Dashboard input `max=25000`; presets 1000 / 5000 / 25000.

## 2. Live play strength

- Easy: depth 1, epsilon 0.35 (unchanged).
- Normal: depth 2, epsilon 0.10 (unchanged).
- Hard: depth = SEARCH_DEPTH (6), epsilon 0.
- Adaptive: epsilon 0. If the human’s win rate > 0.6 after 3+ games, depth = SEARCH_DEPTH + 1. Risk still drops against aggressive / king-rush styles.

`ai_turn` and offline `generateTurn` call `select(..., explore=False)`. Training `play_game(..., explore=True)` is unchanged.

Ollama may write reasoning. It must not change the chosen move.

## 3. Opening book

`book_move(board, history, rng=None)` calls `BOOK.lookup_move`, parses, returns a legal `Move` or `None`. Forced captures already filter the book (lookup only returns legal notations).

Server: reconstruct ply notations, then book, then search.
Phone: `OpeningBook` ports `OPENING_LINES` and the same normalize/lookup. `LocalAgent.select` takes `history`. Offline API passes `match.moves`.

CFE1 stays weights-only. Phone strength from the book is in Kotlin; phone strength from training is the downloaded artifact after the 25k save.

## 4. Long train

Do not cancel an in-flight job. When idle, start `start_training(games=25000, depth=2, epsilon=0.25, evaluate=False, curriculum="curriculum", use_book=True)`. That save becomes the next manifest version the phone downloads.

## 5. Tests

- `clamp_games(25000) == 25000`, `clamp_games(30000) == 25000`, `clamp_games(1) == 5`.
- Adaptive default epsilon is 0; winning human increases depth, not epsilon.
- After `["11-15"]`, `book_move` returns a legal White book reply.
- `ai_turn` after `11-15` plays a book move; a mocked Ollama alternative is ignored.
- Existing `test_ollama_can_override...` is replaced by “narrates but does not override”.
- Kotlin: book reply after `11-15`; adaptive epsilon 0; hard depth 6.
- `FEATURE_SIZE` / artifact tests stay green.

## Success

- Dashboard accepts 25000.
- Adaptive no longer blunders on purpose.
- First-book-phase games play theory on server and phone.
- A 25k run can be started and, when saved, syncs to the emulator via the existing engine download.
