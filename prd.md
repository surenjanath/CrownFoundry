# Product Requirements Document: Adaptive AI Checkers

## 1. Executive Summary

**Product Vision:** A mobile-first Checkers game where the user competes against a local AI. The AI not only plays the game but uses a Reinforcement Learning (RL) loop managed by a centralized backend to adapt to the user's playstyle, optimize its strategy, and track its evolving win rate over time.
**Target Stack:** Mobile Frontend (React Native/Expo), Backend API (Django + Django REST Framework), Database (PostgreSQL), AI Engine (Ollama for strategic reasoning + Deep Q-Learning model for move evaluation).

---

## 2. System Architecture

The system is divided into three distinct layers to separate the user interface, state management, and AI computation.

### The Mobile Frontend

* **Role:** Renders the 8x8 interactive board, handles valid move highlighting, captures user inputs, and displays AI thought processes/chat.
* **State:** Local game state for UI rendering only. Relies on the backend to validate moves and determine win states.

### The Django Backend (The Brain & Referee)

* **Role:** Acts as the central hub. It validates game rules, stores match histories, calculates analytics, and orchestrates the AI's turns.
* **RL Engine Integration:** Hosts the Q-Learning algorithm (or Deep Q-Network) that evaluates the mathematical strength of board states.

### The Local AI Engine (Ollama)

* **Role:** Provides the "personality" and strategic chain-of-thought.
* **Workflow:** Django sends the current board state and the RL engine's top mathematically evaluated moves to Ollama. Ollama selects the final move, generating human-readable reasoning (e.g., "I am sacrificing this pawn to trap the king").

---

## 3. The Reinforcement Learning (RL) Loop

To ensure the AI actually "learns and learns until it beats you," the system uses a hybrid RL-LLM approach.

1. **State Representation:** The board is encoded as a matrix (e.g., 32 playable squares containing Red, Black, Kings, or Empty).
2. **Reward System:**
* **+10:** Winning the game.
* **+2:** Capturing an opponent's piece.
* **+3:** Crowning a King.
* **-2:** Losing a piece.
* **-10:** Losing the game.


3. **The Q-Update (Learning):** After every game, the Django backend runs an asynchronous background task (e.g., using Celery) to update the Q-values/policy network. If the AI lost, the sequence of moves that led to the loss receives a negative weight penalty, ensuring it avoids that path in future matches.

---

## 4. Backend Database Schema (Django Models)

These core models track the AI's progression and handle game persistence.

| Model | Purpose | Key Fields |
| --- | --- | --- |
| `PlayerProfile` | Tracks the human user's stats. | `user_id`, `total_games`, `win_rate`, `elo_rating` |
| `Match` | Represents a single game session. | `match_id`, `start_time`, `end_time`, `winner`, `total_turns` |
| `GameState` | Tracks the board state per turn. | `match_id`, `turn_number`, `board_fen` (text state), `current_player` |
| `AIMoveMemory` | Logs the AI's logic for the RL loop. | `state_id`, `chosen_move`, `ollama_reasoning`, `reward_score` |
| `RLPolicyWeights` | Stores the current mathematical weights. | `version`, `model_blob` (saved network state), `last_updated` |

---

## 5. Core API Specifications

The mobile app communicates with the Django backend via stateless REST API endpoints.

* **`POST /api/match/start/`**
* *Payload:* `{ "difficulty": "adaptive" }`
* *Response:* `{ "match_id": "uuid", "initial_board": "FEN_string" }`


* **`POST /api/match/move/`**
* *Payload:* `{ "match_id": "uuid", "player_move": "C3-D4" }`
* *Response:* `{ "valid": true, "game_over": false, "board_state": "FEN_string" }`


* **`POST /api/ai/generate-turn/`**
* *Payload:* `{ "match_id": "uuid" }`
* *Response:* `{ "ai_move": "F6-E5", "ai_reasoning": "Securing the center.", "new_board": "FEN_string" }`


* **`GET /api/analytics/ai-performance/`**
* *Response:* Returns time-series data on the AI's win rate, average turns to win, and piece-capture ratios against the user.



---

## 6. Analytics & Performance Tracking

To visualize the AI's learning curve, the backend exposes endpoints that calculate:

* **The AI Win/Loss Delta:** A graph showing how many games it took the AI to cross a 50% win rate against the human.
* **Mistake Repetition Rate:** Tracks how often the AI makes a move that previously resulted in a negative reward, proving the RL penalty system is working.
* **Game Length:** Plots the average number of turns per match. As the AI gets smarter, matches should theoretically get longer as it plays better defense, before eventually getting shorter as it learns to execute decisive traps.

---

## 7. Implementation Roadmap

* **Phase 1: The Core Engine (Django + Rules)**
* Set up the Django project and PostgreSQL database.
* Write the Python Checkers rule engine (move validation, jump logic, win detection).


* **Phase 2: API & Local AI Interception**
* Build the DRF endpoints for match creation and state management.
* Script the bridge between Django and the local Ollama API for text-based move generation.


* **Phase 3: The RL Integration**
* Implement the reward tracking models.
* Integrate a Deep Q-Learning algorithm (using PyTorch or TensorFlow within Django) to score board matrices.


* **Phase 4: Mobile App & Visuals**
* Build the React Native UI.
* Wire the UI touch events to the Django API.
* Build the dashboard screen showing the AI's performance stats.