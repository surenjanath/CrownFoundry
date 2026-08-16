<div align="center">
    <h1>CrownFoundry</h1>
    <p>Draughts against an opponent that is still learning, for Android</p>
</div>

---

CrownFoundry is the mobile half of [Adaptive AI Checkers](../prd.md). You play English draughts
against an AI that runs on a Django backend you host yourself: a Q-network scores the positions,
a shallow search looks past them, and a local language model turns the move it settles on into a
sentence you can read.

The interface is [ViMusic](https://github.com/vfsfitvnm/ViMusic)'s, carried over whole — the
vertical navigation rail, the oversized headers, Poppins, the shimmer placeholders, the bottom
sheet menus. There is no Material dependency; every component on screen is drawn by this app.

## What is in it

- **A board** that animates every hop of a multiple jump, marks the pieces that are obliged to
  capture, and corrects itself from the referee when you try something the rules do not allow
- **The opponent's reasoning** under the board after each of its turns, alongside the moves it
  weighed and what it scored them — you can watch it think, and watch it change its mind between
  games
- **Insights**: the AI's win rate against you over time, how long your games run, and how often
  it repeats a move that has already cost it — the three graphs the PRD asks for, drawn from the
  backend's analytics
- **Match history** you can scrub through ply by ply, with the reasoning it gave at the time
- **Themes**: default, tinted by the accent colour, or pure black, in light/dark/system mode,
  with six accents, adjustable corner roundness and four text sizes

## Project layout

| Module            | What it holds |
| ----------------- | ------------- |
| `:app`            | The Android app: Compose UI, the design system carried over from ViMusic, the board and the dashboard |
| `:api`            | A Ktor client for the Django referee, and the DTOs for every payload in [ARCHITECTURE.md](../ARCHITECTURE.md) §5 |
| `:compose-routing`| ViMusic's tiny routing library, unchanged |
| `:compose-persist`| ViMusic's per-screen state cache, unchanged |

No database ships in the app. The backend is the referee and the record; the app holds only your
preferences and the id of the match you are in the middle of.

## Getting it running

Start the backend first — see [`../Backend/README.md`](../Backend/README.md). Then:

```sh
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. It needs JDK 17 and the Android SDK; everything
else is fetched by Gradle.

The app looks for the referee at `http://10.0.2.2:8000` — the host machine as the emulator sees
it. On a physical phone, put your machine's LAN address in **Settings → Backend** instead, and
run the server with `python manage.py runserver 0.0.0.0:8000`. That screen also tells you whether
Ollama is answering.

- `minSdk` 21, `targetSdk` 36
- Kotlin 2.1, Compose 1.7, Ktor 2.3
- No Google Play services, no Firebase, no analytics

### Tests

```sh
./gradlew :api:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest    # needs a device or emulator
```

The unit tests cover the square numbering, the FEN parser, the tap-to-move reduction including
multiple jumps, the chart maths, and every failure the network layer can produce — all against a
fake referee, never a socket.

### Release builds

```sh
./gradlew :app:assembleRelease
```

R8 and resource shrinking do the usual work. Signing is read from a `keystore.properties` at the
repository root, which is never committed:

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Copy [`keystore.properties.example`](./keystore.properties.example) to get started. Without that
file the release build falls back to the debug key, so a fresh clone still builds — it just
produces something that cannot be published.

## Privacy

No account and no analytics. Matches live in the backend you point the app at, which by default
is a server on your own machine. The app itself stores your preferences, a random id that lets
the AI recognise you between games, and the match you have open.

## Acknowledgments

- [**ViMusic**](https://github.com/vfsfitvnm/ViMusic) by vfsfitvnm — the design and the Compose
  foundations this app is built on
- [**Ollama**](https://ollama.com) — the local model behind the opponent's voice
- [**Ionicons**](https://ionic.io/ionicons) — the icon set
- [**Poppins**](https://fonts.google.com/specimen/Poppins) — the typeface

## License

GPL-3.0, inherited from ViMusic, whose design system and Compose foundations this app builds on.
See [LICENSE](./LICENSE).
