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
```

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

## 6. Django models (`Backend/game/models.py`, `Backend/ai/models.py`)

| Model | Key fields |
| --- | --- |
| `PlayerProfile` | `player_id (uuid)`, `total_games`, `wins`, `losses`, `draws`, `win_rate`, `elo_rating`, `style_aggression`, `style_king_rush` |
| `Match` | `match_id (uuid)`, `player`, `difficulty`, `status`, `start_time`, `end_time`, `winner`, `total_turns`, `ai_captures`, `human_captures` |
| `GameState` | `match`, `turn_number`, `board_fen`, `current_player`, `move_notation`, `created_at` |
| `AIMoveMemory` | `state`, `match`, `chosen_move`, `ollama_reasoning`, `reward_score`, `q_value`, `was_repeat_mistake`, `considered_moves (json)` |
| `RLPolicyWeights` | `version`, `model_blob`, `games_trained`, `last_loss`, `last_updated`, `is_active` |
| `TrainingRun` | `policy_version`, `kind (online/post_match/self_play)`, `games`, `loss`, `created_at` |

---

## 7. Mobile module layout (`Mobile/`)

```
:app                Compose UI, design system carried over from ViMusic/Wye, no Room.
:api                Ktor client for the Django API + serializable DTOs (replaces :hackernews).
:compose-routing    unchanged
:compose-persist    unchanged
```

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
