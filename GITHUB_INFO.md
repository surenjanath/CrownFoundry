# 🐙 GitHub Repository Information & Setup Guide

This document contains everything needed to configure, brand, and publish the **CrownFoundry** repository on GitHub.

---

## 🏷 Repository Metadata

| Property | Value |
|---|---|
| **Repository Name** | `CrownFoundry` (or `crown-foundry`) |
| **Short Description (Tagline)** | 👑 Adaptive AI Checkers: English draughts on Jetpack Compose with a Django RL engine and Ollama move commentary |
| **Website / Homepage** | *(Optional: link to personal portfolio or documentation page)* |
| **Visibility** | Public |
| **License** | Apache-2.0 |

### 🔖 Topics / Tags
Add the following tags to the GitHub repository to optimize discoverability:

```text
reinforcement-learning, checkers, draughts, android, jetpack-compose, kotlin, django, python, ollama, llm, q-learning, ai-game, machine-learning, mobile-app, game-ai
```

---

## 🖼 Social Preview & Media Assets

All UI screenshots captured directly from the Android emulator are stored under [`docs/screenshots/`](./docs/screenshots/):

| Screenshot | Screen & Purpose | File |
|---|---|---|
| 01 | **Play Dashboard**: Difficulty picker, opponent Elo, policy version, backend health | [`01_play_screen.png`](./docs/screenshots/01_play_screen.png) |
| 02 | **Matches History**: Timeline of completed and active matches with outcomes | [`02_matches_screen.png`](./docs/screenshots/02_matches_screen.png) |
| 03 | **Insights Dashboard**: Win rate, mistake repetition rate, learning curve metrics | [`03_insights_screen.png`](./docs/screenshots/03_insights_screen.png) |
| 04 | **Interactive Board**: Full 8x8 draughts board with move indicators | [`04_gameplay_initial.png`](./docs/screenshots/04_gameplay_initial.png) |
| 05 | **Live AI Reasoning**: Ollama LLM natural-language narration + Q-values | [`05_gameplay_active_ai_reasoning.png`](./docs/screenshots/05_gameplay_active_ai_reasoning.png) |
| 06 | **Settings & Rules**: Custom appearance, accent colors, and rule switches | [`06_settings_screen.png`](./docs/screenshots/06_settings_screen.png) |

---

## 📦 GitHub Release Template (`v1.0.0`)

When creating the first release tag (`v1.0.0`) on GitHub:

### Release Title:
```
CrownFoundry v1.0.0 — Adaptive AI Checkers with Real-time RL & LLM Commentary
```

### Release Description:
```markdown
# 👑 CrownFoundry v1.0.0

We're excited to release **CrownFoundry v1.0.0** — an adaptive English draughts platform combining an Android client with a self-learning Django reinforcement-learning backend and Ollama LLM commentary.

### 🌟 Highlights
- **Continuous Q-Learning**: The opponent updates its policy online every move and post-match.
- **Natural Language Move Commentary**: Integrated Ollama LLM bridge explains tactical decisions in real time.
- **Pure Python Rules Engine**: Refereed backend matching published perft combinatorial counts.
- **Jetpack Compose UI**: Fast, fluid board interactions with hop animations and coronation flourishes.
- **Insights & Metrics**: Full tracking of win rates, mistake repetition, and Elo progression.

### 📥 Downloads & Assets
- Attach `APK/CrownFoundry.apk` as a downloadable binary release asset.
```

---

## ⚙️ Recommended GitHub Actions CI (`.github/workflows/ci.yml`)

You can enable automated testing on every pull request and push:

```yaml
name: CrownFoundry CI

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]

jobs:
  backend-tests:
    name: Backend Unit Tests & Referee Perft
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up Python
        uses: actions/setup-python@v5
        with:
          python-version: '3.11'
          cache: 'pip'
      - name: Install dependencies
        run: |
          cd Backend
          python -m pip install --upgrade pip
          pip install -r requirements.txt
      - name: Run Django Tests
        run: |
          cd Backend
          python manage.py test
      - name: Run Perft Combinatorics Check
        run: |
          python tools/perft.py --depth 6

  mobile-tests:
    name: Mobile App & API Unit Tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle
      - name: Grant execute permission for gradlew
        run: chmod +x Mobile/gradlew
      - name: Run Unit Tests
        run: |
          cd Mobile
          ./gradlew :api:testDebugUnitTest :app:testDebugUnitTest
```
