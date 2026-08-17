# CrownFoundry — Architecture & Shared Contract

Adaptive AI Checkers. This document is the **single source of truth** shared by the backend and
mobile workstreams. Anything not written here is a local implementation detail.

Deviation from `prd.md`: the mobile client is **Android / Kotlin / Jetpack Compose**, not React
Native. The `Mobile/` folder already held a Compose app ("Wye") whose design system is being
carried over, per the project owner's instruction.

---

## 1. Layout

```
Backend/          Django 6 + DRF. Rules referee, persistence, RL engine, Ollama bridge, analytics.
Mobile/           Android app (Kotlin, Compose). Board UI, AI reasoning panel, insights dashboard.
                  Also carries a full offline copy of the referee and the policy (see §5a, §8).
```

**Two referees, one contract.** The backend is authoritative, but it is not always reachable, so
the rules engine, the feature encoder, the Q-network and its optimiser all exist twice: once in
Python under `Backend/`, once in Kotlin under `Mobile/engine/`. The Kotlin half is a port, not an
interpretation — where the two disagree the backend is right by definition, because every offline
game is replayed through it on sync. The ports are held to that by tests that assert against values
the Python produced, not against themselves:

| Ported | Python | Kotlin | Pinned by |
| --- | --- | --- | --- |
| Rules & notation | `game/engine/` | `engine/Squares.kt`, `engine/Board.kt` | perft counts to depth 5, both variants |
| Feature encoder | `ai/features.py` | `engine/Features.kt` | every scalar, both perspectives, in `FeaturesTest` |
| Q-network | `ai/policy.py` | `engine/Network.kt` | a trained fixture + its numpy outputs, in `ArtifactTest` |
| Search & knobs | `ai/agent.py` | `engine/Agent.kt` | a material-only evaluator, in `AgentTest` |
| Credit assignment | `ai/agent.py` | `engine/Learning.kt` | reward and return tables printed from the backend |
| Narration fallback | `ai/ollama.py` | `engine/Narrator.kt` | — |

---

## 2. Board & move notation (English draughts / American checkers)

**Squares.** The 32 playable (dark) squares are numbered `1..32`, row-major from the top-left of
the board as Black sees it. Row 0 holds `1,2,3,4`; row 7 holds `29,30,31,32`.

Mapping between square number `n` (1-based) and `(row, col)` on the 8x8 grid:

```
row = (n - 1) // 4
idx = (n - 1) % 4
col = 2*idx + (0 if row % 2 == 1 else 1)     # dark squares only
```

(Equivalently: on even rows the dark squares are columns 1,3,5,7; on odd rows 0,2,4,6.)

**Sides.** `black` and `white`.
- Black men start on squares `1..12`, advance toward **higher** numbers, promote on `29..32`.
- White men start on squares `21..32`, advance toward **lower** numbers, promote on `1..4`.
- **Black moves first.** The **human is Black**; the **AI is White**.
- The mobile client renders Black at the **bottom** of the screen (board is flipped for the human).

**Rules (strict English draughts).**
- Men move one step diagonally forward; kings move one step diagonally in any of the 4 directions.
- Kings do **not** fly (no long-range moves).
- **Captures are mandatory.** If any capture exists, only captures are legal.
- Multi-jumps must be played to completion; a man that reaches the back rank **mid-jump stops and
  is crowned** (the jump sequence ends there).
- Crowning ends the turn.
- Win: opponent has no pieces, or no legal moves.
- Draw: 40 plies with no capture and no promotion, or three-fold position repetition.

**FEN string.** PDN-style:

```
<side-to-move>:W<white squares>:B<black squares>
```

Kings are prefixed with `K`. Example, the opening position with Black to move:

```
B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12
```

`<side-to-move>` is `B` or `W`. Empty piece lists render as `W` / `B` with nothing after them.

**Move string (canonical).**
- Simple move: `11-15`
- Single jump: `11x18`
- Multi jump: `11x18x25` (every landing square, in order)

The API also accepts `{"from": 11, "to": 15}`; the server resolves it to the unique legal move,
and returns `400` with `ambiguous: true` if more than one legal jump path matches.

---

## 3. Backend engine interface (`Backend/game/engine/`)

Pure Python, zero Django imports, fully unit-testable.

```python
# board.py
BLACK = "black"; WHITE = "white"

@dataclass(frozen=True)
class Piece:      side: str; king: bool

@dataclass(frozen=True)
class Move:
    origin: int                 # 1..32
    destination: int            # 1..32
    path: tuple[int, ...]       # landing squares after origin, e.g. (18, 25)
    captures: tuple[int, ...]   # squares of captured pieces, in order
    crowned: bool               # this move promotes the mover
    @property
    def is_jump(self) -> bool
    def notation(self) -> str   # "11-15" / "11x18x25"

class Board:
    squares: dict[int, Piece]           # only occupied squares
    side_to_move: str
    plies_since_progress: int

    @classmethod
    def initial(cls) -> "Board"
    @classmethod
    def from_fen(cls, fen: str) -> "Board"
    def to_fen(self) -> str
    def legal_moves(self) -> list[Move]
    def apply(self, move: Move) -> "Board"          # returns a NEW board, side flipped
    def parse_move(self, text: str) -> Move         # raises IllegalMove
    def resolve(self, origin: int, dest: int) -> Move
    def winner(self) -> str | None                  # "black" | "white" | "draw" | None
    def is_terminal(self) -> bool
    def piece_counts(self) -> dict                  # {"black_men","black_kings","white_men","white_kings"}
```

`IllegalMove(ValueError)` is raised for anything not in `legal_moves()`.

---

## 4. RL interface (`Backend/ai/`)

```python
# features.py
FEATURE_SIZE: int
def encode(board: Board, perspective: str) -> np.ndarray   # shape (FEATURE_SIZE,), float32

# policy.py
class QNetwork:                      # numpy MLP, no heavy deps
    def predict(self, x) -> np.ndarray
    def train_batch(self, x, y) -> float          # returns loss
    def to_blob(self) -> bytes
    @classmethod
    def from_blob(cls, blob: bytes) -> "QNetwork"

# agent.py
class AdaptiveAgent:
    def select(self, board: Board, *, explore: bool) -> tuple[Move, list[ScoredMove]]
    def observe(self, transition: Transition) -> None      # online replay push + step
    def learn_from_match(self, match_id) -> TrainingReport  # post-game batch update
```

**Rewards** (from the AI's perspective, per `prd.md`): win `+10`, capture `+2`, crown `+3`,
piece lost `-2`, loss `-10`.

**Learning happens at three cadences:**
1. **Online** — every AI move pushes a transition into replay and runs a small gradient step.
2. **Post-match** — a background task replays the finished game with terminal rewards and
   penalises the move sequence that led to a loss.
3. **Self-play** — `python manage.py train_selfplay --games N` bootstraps and continually
   improves the policy off-line.

**Opponent modelling.** `PlayerProfile` accumulates the human's style (aggression = captures per
turn, king-rush tendency, average reply depth). The agent's exploration rate and search depth are
tuned per-opponent so it converges on beating *that* player.

---

## 5. REST API

Base path `/api/`. All bodies are JSON. All responses include `"ok": true|false`.
Errors: `{"ok": false, "error": "<code>", "detail": "<human readable>"}` with a 4xx status.

### `GET /api/health/`
```json
{"ok": true, "version": "1.0.0", "ollama": {"available": true, "model": "qwen3.5:9b"},
 "policy_version": 12}
```

### `POST /api/match/start/`
Request: `{"difficulty": "adaptive", "player_id": "<optional uuid>"}`
`difficulty` ∈ `easy | normal | hard | adaptive`.
```json
{"ok": true,
 "match_id": "uuid",
 "initial_board": "B:W21,...:B1,...",
 "board": {"fen": "...", "side_to_move": "black", "pieces": [{"square": 1, "side": "black", "king": false}, ...]},
 "legal_moves": [{"notation": "11-15", "from": 11, "to": 15, "captures": []}],
 "turn_number": 0,
 "ai": {"policy_version": 12, "games_trained": 340, "win_rate": 0.46, "elo": 1180}}
```

### `GET /api/match/<uuid>/`
Same shape as above plus `"history": [{"turn": 1, "side": "black", "move": "11-15", "fen": "...", "reasoning": null}]`,
`"status": "active"|"finished"`, `"winner": null|"black"|"white"|"draw"`.

### `POST /api/match/move/`
Request: `{"match_id": "uuid", "player_move": "11-15"}` or `{"match_id": "...", "from": 11, "to": 15}`
```json
{"ok": true, "valid": true, "game_over": false, "winner": null,
 "board_state": "W:W21,...:B1,...",
 "board": {...}, "legal_moves": [...],
 "applied_move": {"notation": "11-15", "captures": [], "crowned": false},
 "turn_number": 1}
```
Illegal move → `400` `{"ok": false, "valid": false, "error": "illegal_move", "legal_moves": [...]}`.

### `POST /api/ai/generate-turn/`
Request: `{"match_id": "uuid"}`
```json
{"ok": true,
 "ai_move": "24-19",
 "ai_reasoning": "Holding the centre so your right flank has nothing to trade into.",
 "reasoning_source": "ollama" | "heuristic",
 "new_board": "B:W21,...:B1,...",
 "board": {...}, "legal_moves": [...],
 "evaluation": {"q_value": 0.41, "confidence": 0.78,
                "considered": [{"notation": "24-19", "q": 0.41}, {"notation": "23-18", "q": 0.36}]},
 "game_over": false, "winner": null, "turn_number": 2,
 "captures": [], "crowned": false}
```

### `GET /api/matches/?player_id=<uuid>&limit=50`
Newest first. Powers the Matches tab.
```json
{"ok": true,
 "matches": [{"match_id": "uuid", "start_time": "2026-08-16T11:00:00Z", "end_time": null,
              "status": "active", "winner": null, "total_turns": 12, "difficulty": "adaptive",
              "ai_captures": 2, "human_captures": 3}]}
```

### `POST /api/match/<uuid>/resign/`
`{"ok": true, "game_over": true, "winner": "white"}`

### `GET /api/analytics/ai-performance/`
```json
{"ok": true,
 "summary": {"total_matches": 41, "ai_wins": 19, "human_wins": 20, "draws": 2,
             "ai_win_rate": 0.463, "elo": 1180, "policy_version": 12,
             "games_to_50_percent": null, "avg_turns": 38.2,
             "mistake_repetition_rate": 0.07, "capture_ratio": 1.12},
 "win_rate_series": [{"match_index": 1, "cumulative_win_rate": 0.0, "rolling_win_rate": 0.0, "result": "loss"}],
 "game_length_series": [{"match_index": 1, "turns": 44}],
 "mistake_series": [{"match_index": 1, "repeated_mistakes": 2, "rate": 0.09}],
 "capture_series": [{"match_index": 1, "ai_captures": 4, "human_captures": 7}],
 "training": [{"policy_version": 3, "loss": 0.11, "games_trained": 120, "updated_at": "..."}]}
```

### `GET /api/analytics/summary/`
The `summary` object above, alone — cheap poll for the Play tab's status card.

---

## 5a. Engine distribution (offline mode)

Three endpoints, and the whole offline story runs through them. Served by `Backend/ai/views.py`,
routed under `/api/ai/`.

### `GET /api/ai/engine/manifest/`
What the server's current policy is. The device compares `version` against what it holds and needs
nothing else to decide whether it is stale.

```json
{"ok": true, "format": 1, "version": 28, "architecture": "148-128-64-1", "feature_size": 148,
 "elo": 1127, "games_trained": 41, "last_loss": 0.31, "size_bytes": 109966,
 "checksum": "<sha256>", "created_at": "...", "url": "/api/ai/engine/download/"}
```

Answers even on a server that has never trained: `version` is then `0`, and the client is better
off with an untrained engine it can play against than with no offline mode at all.

### `GET /api/ai/engine/download/`
The policy as a **CFE1** artifact — `application/octet-stream`, `ETag` = checksum, so a re-check
costs one 304. About 110 KB for the shipped architecture.

```
offset  size          field
0       4             magic, "CFE1"
4       4             uint32 little-endian header length
8       header_len    UTF-8 JSON header (the manifest fields, plus lr/beta1/beta2/eps/grad_clip/
                      huber_delta/step_count)
...     4 * n         float32 little-endian payload, layer by layer
```

Layers are written in order, each as `W` (`fan_in * fan_out`, row-major) then `b` (`fan_out`).
Adam moments are **not** shipped: the device restarts its optimiser state on every model swap,
which is what you want when the weights underneath it just changed.

The device may write this format back out after local fine-tuning, adding `base_version`,
`local_games` and `local_loss` to the header. The backend reader ignores keys it does not know.

**Reference implementations.** `Backend/ai/export.py` writes it; `Mobile/engine/.../Artifact.kt`
reads it. They are pinned against each other by
`Mobile/engine/src/test/resources/policy-fixture.cfe` — a real trained network plus the values
numpy computed for it, asserted in `ArtifactTest`. If that test passes, the AI on the device is
the AI on the server.

### `POST /api/ai/engine/sync/`
Games the device refereed itself.

```json
{"player_id": "<uuid>", "matches": [
  {"local_id": "<client id>", "difficulty": "hard", "rules": {...},
   "moves": ["11-15", "22-18", "15x22"], "resigned_by": "black",
   "started_at": "...", "finished_at": "...", "engine_version": 28}
]}
```

```json
{"ok": true, "imported": 1, "player_id": "<uuid>",
 "accepted": [{"local_id": "...", "match_id": "<uuid>", "duplicate": false}],
 "rejected": [{"local_id": "...", "index": 1, "error": "illegal_move", "detail": "ply 2: ..."}],
 "engine": { ...the manifest... }}
```

Rules the client depends on:

* **Nothing is taken on faith.** Every ply is replayed through the real engine here. A game that
  does not replay is rejected whole, not half-imported — per-match atomicity, so one bad move list
  does not cost the player the other nine in the same outbox.
* **The engine decides the result**, not the client. A resignation is the exception, because it
  leaves no trace in the move list; that is what `resigned_by` is for.
* **Imports are idempotent** on `(player, local_id)`, so a phone that loses the response and
  retries its outbox gets `duplicate: true` rather than a second copy.
* **What survives becomes ordinary `Match` rows** (`origin = "offline"`), so post-match training,
  analytics and match history pick them up with no idea they happened on a plane.
* The response carries the current manifest, so a sync that triggers training tells the device it
  is stale in the same round trip.
* At most 50 matches and 400 plies per game.

---

## 6. Django models (`Backend/game/models.py`, `Backend/ai/models.py`)

| Model | Key fields |
| --- | --- |
| `PlayerProfile` | `player_id (uuid)`, `total_games`, `wins`, `losses`, `draws`, `win_rate`, `elo_rating`, `style_aggression`, `style_king_rush` |
| `Match` | `match_id (uuid)`, `player`, `difficulty`, `status`, `start_time`, `end_time`, `winner`, `total_turns`, `ai_captures`, `human_captures`, `origin (server/offline)`, `client_ref` |
| `GameState` | `match`, `turn_number`, `board_fen`, `current_player`, `move_notation`, `created_at` |
| `AIMoveMemory` | `state`, `match`, `chosen_move`, `ollama_reasoning`, `reward_score`, `q_value`, `was_repeat_mistake`, `considered_moves (json)` |
| `RLPolicyWeights` | `version`, `model_blob`, `games_trained`, `last_loss`, `last_updated`, `is_active` |
| `TrainingRun` | `policy_version`, `kind (online/post_match/self_play)`, `games`, `loss`, `created_at` |

---

## 7. Mobile module layout (`Mobile/`)

```
:app                Compose UI, design system carried over from ViMusic/Wye, no Room.
:api                Ktor client for the Django API + serializable DTOs (replaces :hackernews).
:engine             The offline brain: rules, feature encoder, Q-network, search, learner.
                    Pure Kotlin, no Android APIs beyond the library packaging, no UI, no network.
:compose-routing    unchanged
:compose-persist    unchanged
```

`:engine` depends on nothing in the project. That is deliberate — it is the piece with a mirror on
the other side of the wire, and keeping it free of Ktor, Compose and Android context makes it
testable against the Python directly.

Package rename: `com.surenjanath.wye` → `com.surenjanath.crownfoundry`.
API package: `com.surenjanath.crownfoundry.api`.

**Screens.** Navigation rail tabs (the top icon button opens Settings, as before):

| Tab | Icon | Screen |
| --- | --- | --- |
| 0 Play | `sparkles` | Start/resume a match, difficulty picker, AI status card |
| 1 Matches | `time` | Match history list, tap to review |
| 2 Insights | `trending` | The AI's learning curve — win rate, game length, mistake repetition |

`gameRoute` is a stacked route holding the live board. `settingsRoute` keeps only Appearance +
About + a Backend URL field.

**Design rules.** Reuse `LocalAppearance` (`colorPalette`, `typography`, `thumbnailShape`) for
everything. No Material components. Board squares use `colorPalette.background2` / `background1`;
the AI's pieces use `colorPalette.text`, the human's use `colorPalette.accent`; legal-move hints
are accent at 25% alpha; the selected square gets an accent ring.

**Backend base URL.** Default `http://10.0.2.2:8000` (emulator loopback), overridable in Settings,
stored in SharedPreferences under `backendUrlKey`.

---

## 8. Offline mode

The app plays a full game with no connection, against the same policy the server serves, and keeps
learning while it does. Three pieces make that work.

### The seam

`CheckersApi` is the whole contract between the UI and the referee. `OfflineCheckersApi` implements
it against `:engine` and the local store, so the board, the turn machine, the animations, the match
history and the analytics screens work offline without a line of UI knowing why. `HybridCheckersApi`
sits in front of both and decides which one answers:

* **A match belongs to whoever started it.** An `offline-` id is refereed on the device forever; a
  server uuid goes to the server forever. A game is never migrated mid-flight — the two referees
  keep separate state, and a position that exists in both can disagree with itself.
* **Only *starting* a match may fall back.** If the referee cannot be reached when the player taps
  Play, they get an offline game instead of an error. Everything else keeps the existing retry path.
* **Only connectivity qualifies as a fall-back reason.** A referee that answered and said no —
  illegal move, finished match, unknown id — has given a real answer, and replacing it with a
  locally invented one would hide a genuine disagreement.
* Read-only calls (history, analytics, health) degrade quietly, merging both sides when both answer.

### Engine state

`EngineStore` owns the installed artifact and answers the question the Play screen asks:

| Status | Meaning | Offline play | Headline |
| --- | --- | --- | --- |
| `Missing` | never downloaded | ✗ | *AI engine needs updating* |
| `Ready` | current with the last manifest seen | ✓ | *AI engine v14 · ready offline* |
| `Stale` | server has trained past it | ✓ | *AI engine needs updating* |
| `Incompatible` | artifact format or feature size this build cannot read | ✗ | *AI engine needs updating* |

**A stale engine is still a good opponent** — same weights as the last sync, same search — so it is
badged, not blocked. Only `Missing` and `Incompatible` actually stop a game, and both say which way
to fix it. `EngineSync` fetches the manifest, compares versions, verifies a download against its
checksum *and* its advertised size before installing, and writes through a staging file so a
download killed halfway leaves the previous engine intact.

### The learning loop

```
device plays offline  ──►  local Monte-Carlo pass fine-tunes the on-device weights
        │                  (same rewards, same returns, same loss tail as the server)
        │
        └──►  outbox  ──►  POST /api/ai/engine/sync/  ──►  server replays every ply,
                                                            imports it as a real Match,
                                                            queues post-match training
                                                                    │
   GET /api/ai/engine/manifest/  ◄────────────────────────────  new policy version
        │
        └──►  GET /api/ai/engine/download/  ──►  installed, local fine-tuning reset
```

The device fine-tunes but never *replaces* the policy: it is one player's games, and a phone left
offline for a week would otherwise drift into a private fork. `EngineHeader.base_version` records
which server policy the local weights came from, so "am I stale?" stays answerable after local
training, and the badge reads `v14 +3` — server version, plus offline games learned from here.

Local training runs at the game-over dialog rather than mid-turn, so the next game genuinely faces
a policy that saw the last one. Games the server rejects permanently (`illegal_move`,
`empty_match`, …) are dropped from the outbox rather than retried forever; anything else — a 500, a
timeout, a proxy — stays queued.

### Cost

Measured by `SearchBudgetTest`, which prints what it finds rather than asserting a number nobody
can check:

| | |
| --- | --- |
| Artifact | ~110 KB (148-128-64-1, float32, no optimiser moments) |
| Forward pass | ~3 µs |
| A full turn at depth 4 | single-digit ms |
| Post-match training | a few ms |
| Replay buffer on disk | ≤ 2000 transitions, ~1.2 MB |

Because a turn is that cheap, the device runs the **server's exact search settings** — same depth,
same 4000-node budget — rather than a handicapped version. The offline opponent is not a weaker
opponent; what it lacks is the Ollama commentary, and it says so.

### Settings

Settings → Offline: installed version and status, *Update now*, keep-the-engine-current (default
on), always-play-offline (default off), learn-from-offline-games (default on), the outbox count,
and last checked / downloaded / trained timestamps.
