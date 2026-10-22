# War Nation

Top-down real-time squad tactics game for Android, written in Kotlin on libGDX 1.14, with a desktop
(LWJGL3) build for fast iteration. Gameplay is described in `README.md`.

## Build, run, test

```sh
./gradlew :core:test              # headless simulation tests (fast, the main safety net)
./gradlew :desktop:run            # play on desktop (working dir = assets/)
./gradlew :android:assembleDebug  # APK -> android/build/outputs/apk/debug/android-debug.apk
./gradlew :android:installDebug   # install on a USB-connected phone
```

- Gradle 8.14 can't run on the system JDK 25, so `gradle.properties` pins `org.gradle.java.home` to a local
  JDK 21 (`~/.jdks/jdk-21.0.12.1+1`). The Android SDK is at `~/Android/Sdk` (`local.properties`, git-ignored).
- compileSdk/targetSdk 36 (libGDX 1.14's androidx dependency requires it); bytecode targets Java 17.
- libGDX Android natives (`libgdx.so`, `libgdx-freetype.so`) are unpacked from jars into `android/libs/` by
  the `copyAndroidNatives` task; new native extensions must be added to the `natives` configuration.

## Layout

All game code is in `core/src/main/kotlin/com/warnation/`; `desktop/` and `android/` are thin launchers.

- `Battle` — the simulation: orders, player/enemy AI, vision, targeting, projectiles, explosions, win/lose.
  No rendering or input; everything is unit-testable.
- `Terrain` — tile grid (40×30 × 64px), obstacle generation, A* pathfinding, line of sight, cover,
  collision push-out, field-of-fire ray casts.
- `Soldier` / `SoldierType` — per-unit state; `SoldierType` is the stat table (robots get scaled-down stats).
- `GameScreen` — rendering and touch input for a battle (HUD, selection, fire lines, vision cones).
- `HomeScreen` — Scene2D title screen and squad picker. Widget styles are built in code (no skin file).
- `Squad` — chosen loadout and win/loss record, persisted via libGDX `Preferences`.
- `Campaign` — the mission list (`MISSIONS`: title, briefing, map seed, robot squads) and saved progress.
  `Battle(enemySquads = ..., mapSeed = ...)` builds a mission; skirmishes use `Battle.SKIRMISH` and a random map.
- `WarNationGame`, `Assets`, `Fonts` — screen switching and shared resources.
- Tests: `core/src/test/kotlin/com/warnation/` (`BattleTest`, `SquadTest`, `CampaignTest`; `memoryPrefs()` in
  `TestPrefs.kt` stands in for `Preferences`).

## Conventions

- Keep game rules in `Battle`/`Terrain` and drawing in `GameScreen`. Rules shown on screen (fire lines,
  cover, fields of fire) must call the same `Battle`/`Terrain` functions the simulation uses, so the visuals
  can't drift from the real behaviour.
- New mechanics get a `BattleTest` that builds a minimal scenario: `Battle(withObstacles = false)` with
  hand-placed soldiers and `terrain.addObstacle(...)`. Remember soldiers only see inside their forward
  cone, so set `angle` when a test needs two soldiers to notice each other.
- `squadThatAttacksEnemyByEnemyCanWin` prints win/survivor stats for 20 random battles; check it after
  balance changes. `missionsGetHarderButStayWinnable` does the same per campaign mission.
- Tuning constants live in the `companion object` of `Battle`, `Terrain` and `GameScreen`; unit stats in
  `SoldierType`.
- UI sizes use density-independent pixels via `fonts.dp(...)`; fonts are FreeType-generated at device
  density (`Fonts`).
- Assets: Kenney "Top-down Shooter" (CC0) sprites and OFL fonts in `assets/`; keep license files alongside.

## Gotchas (each has bitten this project)

- `SpriteBatch.end()` disables GL blending. Begin shape passes with `GameScreen.beginShapes(...)`, which
  re-enables it, or translucent shapes draw opaque.
- `Vector2.epsilonEquals` treats NaN as equal to everything; don't use NaN as an "uncomputed" sentinel.
- Switch screens only via `WarNationGame.showHome()` / `startBattle()`. They defer to the next frame so a
  screen isn't disposed while its own input handler is still running.
- Overlapping translucent areas (fields of fire, vision cones) are merged with a depth-buffer trick in
  `drawCones`; drawing them without it makes overlaps stack brighter.

## Visual checks on desktop

`desktop/.../DesktopLauncher.kt` has a dev-only screenshot mode driven by environment variables:

```sh
WARNATION_PREFS_DIR=/abs/scratch/prefs WARNATION_SCENE=battle \
WARNATION_SCREENSHOT=/abs/scratch/shot.png WARNATION_SCREENSHOT_FRAME=300 ./gradlew :desktop:run
```

- `WARNATION_SCENE`: unset shows the home screen; `battle` double-taps to select the squad, attack-moves
  toward the nearest robots and pans after it; `select` taps one soldier at `WARNATION_TAP_X`/`_Y`
  (default 340, 545) to show its field of fire; `missions` opens the home screen's missions tab.
- `WARNATION_MISSION` (1-based) makes `battle`/`select` play that campaign mission instead of a skirmish.
- `WARNATION_SCREENSHOT_FRAME` (default 120) is the frame to capture; the app exits afterwards.
- `WARNATION_PREFS_DIR` (absolute path) points preferences at a separate folder.

Always set `WARNATION_PREFS_DIR` for scripted runs. Without it they read and write the real save in
`~/.prefs/war_nation`, which the user plays with.
