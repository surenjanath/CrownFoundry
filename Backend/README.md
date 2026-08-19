# CrownFoundry Backend

The referee, the memory and the brain of Adaptive AI Checkers: a Django 6 + DRF service that
validates every move, stores every match, runs the reinforcement-learning agent that plays White,
and computes the analytics the Insights tab draws.

The Android client in [`../Mobile`](../Mobile) is the only consumer. It holds no rules of its own -
it renders what this service says is on the board and asks it whether a tap was legal.

## The three apps

| App         | What it holds                                                                    |
| ----------- | -------------------------------------------------------------------------------- |
| `game`      | The rules engine, the match/turn persistence, and the whole REST surface except analytics |
| `ai`        | The Q-network, the replay buffer, the Ollama bridge and the self-play trainer      |
| `analytics` | The learning-curve maths behind `/api/analytics/`                                  |

`game/engine/` is pure Python with **zero Django imports**, so it can be unit-tested and driven in
tight self-play loops without a database. It does about 32,000 plies a second - generating every
legal move, applying one, and testing for a terminal position.

| Module                | What it holds                                                             |
| --------------------- | ------------------------------------------------------------------------- |
| `engine/notation.py`  | The 32-square geometry, the step/jump lookup tables, FEN and move strings   |
| `engine/moves.py`     | `Piece`, `Move`, and the generator - mandatory captures, multi-jumps, crowning |
| `engine/board.py`     | `Board`: FEN round-tripping, `apply`, the draw rules, terminal detection     |

## Setting up

Python 3.14 and a virtualenv at `.venv`:

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python manage.py migrate
```

That is enough to run. SQLite lands in `crownfoundry.sqlite3` beside `manage.py`, and Ollama is
optional - without it the AI still plays, it just narrates its moves with the heuristic writer
instead of prose.

For PostgreSQL, point `DATABASE_URL` at it before migrating:

```sh
export DATABASE_URL=postgres://user:password@localhost:5432/crownfoundry
```

## Running

```sh
.venv/bin/python manage.py runserver 0.0.0.0:8000
```

Bind to `0.0.0.0`, not the default `127.0.0.1`: the Android emulator reaches the host through
`10.0.2.2`, and a device on your wifi needs your LAN address. Check it answers:

```sh
curl -s localhost:8000/api/health/
```

```json
{"ok": true, "version": "1.0.0", "ollama": {"available": false, "model": "qwen3.5:9b"},
 "policy_version": 0}
```

`/api/health/` answers even when the AI app is broken - `available` goes false and
`policy_version` goes null rather than the request failing. It is the endpoint to hit first when
the app says it cannot reach the backend.

## Pointing the app at it

The app defaults to `http://10.0.2.2:8000`, which is the emulator's route to your machine's
`localhost`. Nothing to configure if you are running both on the same computer.

On a physical device, find your LAN address and put it in the app under **Settings -> Backend URL**
(no trailing slash):

```sh
ipconfig getifaddr en0        # macOS
hostname -I                   # Linux
```

Both the emulator and a device need cleartext HTTP, which the app already allows. CORS is open by
default (`CORS_ALLOW_ALL_ORIGINS`), so a browser hitting the API for debugging works too.

## Configuration

Everything environment-specific is read from the environment, so the same tree runs on a laptop
and in production without edits.

| Variable                    | Default                     | What it does                            |
| --------------------------- | --------------------------- | --------------------------------------- |
| `CROWNFOUNDRY_SECRET_KEY`   | an insecure dev key         | Django's signing key. Set it in production |
| `CROWNFOUNDRY_DEBUG`        | `true`                      | Django debug mode                        |
| `CROWNFOUNDRY_ALLOWED_HOSTS`| `*`                         | Comma-separated host allowlist           |
| `DATABASE_URL`              | SQLite beside `manage.py`   | A `postgres://` URL switches the backend |
| `OLLAMA_HOST`               | `http://127.0.0.1:11434`    | Where the narrator lives                 |
| `OLLAMA_MODEL`              | `qwen3.5:9b`                | The model it asks                        |
| `OLLAMA_ENABLED`            | `true`                      | Set false to always use the heuristic narrator |
| `CROWNFOUNDRY_SEARCH_DEPTH` | `4`                         | How deep the agent looks                 |
| `CROWNFOUNDRY_DASHBOARD_TOKEN` | empty | Required on train/simulate/evaluate when DEBUG is false |
| `CROWNFOUNDRY_CORS_ORIGINS`    | empty | Comma-separated browser origins when DEBUG is false |

## The API

Base path `/api/`, JSON in and out. Every response carries `"ok"`; errors are
`{"ok": false, "error": "<code>", "detail": "..."}` with a 4xx status. A user mistake never
produces a 500. The full contract is [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §5.

| Method | Path                          | What it does                                        |
| ------ | ----------------------------- | --------------------------------------------------- |
| `GET`  | `/api/health/`                | Liveness, Ollama reachability, active policy version |
| `POST` | `/api/match/start/`           | New match. `{"difficulty": "adaptive", "player_id": "<optional uuid>"}` |
| `GET`  | `/api/match/<uuid>/`          | The live position, the legal moves and the move history |
| `POST` | `/api/match/move/`            | Play the human's move                                |
| `POST` | `/api/ai/generate-turn/`      | Ask the AI for White's reply                         |
| `POST` | `/api/match/<uuid>/resign/`   | Concede                                              |
| `GET`  | `/api/matches/`               | Match history, newest first                          |
| `GET`  | `/api/analytics/ai-performance/` | The learning curve, game lengths, capture ratios  |
| `GET`  | `/api/analytics/summary/`     | Just the summary object - a cheap poll               |

A game from the command line:

```sh
MATCH=$(curl -s -X POST localhost:8000/api/match/start/ \
  -H 'content-type: application/json' -d '{"difficulty":"adaptive"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["match_id"])')

curl -s -X POST localhost:8000/api/match/move/ -H 'content-type: application/json' \
  -d "{\"match_id\":\"$MATCH\",\"player_move\":\"11-15\"}"

curl -s -X POST localhost:8000/api/ai/generate-turn/ -H 'content-type: application/json' \
  -d "{\"match_id\":\"$MATCH\"}"
```

**Board and moves.** Every endpoint that returns a position returns the same two keys, built by
the same helper, so the client can parse one shape everywhere:

```json
{"board": {"fen": "W:W21,...:B1,...", "side_to_move": "white",
           "pieces": [{"square": 1, "side": "black", "king": false}]},
 "legal_moves": [{"notation": "11-15", "from": 11, "to": 15, "captures": [], "crowned": false}]}
```

Moves are sent either as canonical notation (`11-15`, `11x18`, `11x18x25`) or as a `from`/`to`
pair the server resolves. When a `from`/`to` pair matches more than one jump path the server
answers `400` with `"ambiguous": true` and the client must send the full notation instead.

**Illegal moves** come back as `400` with `"error": "illegal_move"` and the current `legal_moves`
attached, so the client can re-sync without another round trip. Playing out of turn is a `409`,
which is also what a double-tapped submit produces - the second one loses cleanly rather than
corrupting the game.

## The rules it enforces

Strict English draughts / American checkers, on the 32 numbered dark squares. The human is Black,
moves first, and is drawn at the bottom of the screen; the AI is White.

- Men move one step diagonally forward, kings one step in any direction. Kings do **not** fly.
- Captures are mandatory. If a jump exists anywhere, only jumps are legal.
- Multi-jumps must be played to completion, so a partial sequence is rejected.
- A man that reaches the back rank **mid-jump stops there and is crowned** - the sequence ends
  even when more captures are on offer. Crowning always ends the turn.
- A win is the opponent having no pieces, or no legal move.
- A draw is 40 plies with no capture and no promotion, or the same position three times.

The two draw rules need memory a FEN has nowhere to put, so the `Match` row also carries
`plies_since_progress` and a `repetition_history` of position hashes since the last irreversible
move. The hashes are Zobrist keys off a fixed seed, which is what lets a game survive a restart.

## Testing

```sh
.venv/bin/python manage.py test          # everything
.venv/bin/python manage.py test game     # the referee: engine, models, API
```

The `game` suite is 147 tests and takes under a second. It covers the engine (move generation,
mandatory captures, multi-jumps, crowning mid-jump, both win conditions, both draw rules, FEN
round-trips, move parsing), a seeded fuzz test that plays random games to completion checking the
invariants at every ply, and the whole REST surface field-by-field against the contract.

`ai.service` is stubbed throughout the API tests, so the referee's suite passes with no Ollama
installed and no trained policy on disk.

```sh
.venv/bin/python manage.py check
.venv/bin/python manage.py makemigrations --check --dry-run
```

## Training the AI

The agent learns at three cadences: a gradient step after every move it plays, a batch replay of
each finished game, and offline self-play you run yourself.

```sh
.venv/bin/python manage.py train_selfplay --games 200
```

Weights live in the database as `RLPolicyWeights` rows, one per version, with the newest active.
`/api/analytics/ai-performance/` plots what the training did to its win rate.

## Admin

```sh
.venv/bin/python manage.py createsuperuser
```

`/admin/` lists players, matches and every recorded turn, which is the quickest way to see why a
game ended the way it did.
