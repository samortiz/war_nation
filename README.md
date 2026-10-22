# War Nation

Top-down real-time tactics game for Android (libGDX + Kotlin), with a desktop build for quick testing.

## Play
- The home screen shows your record and the squad panel: use **-** / **+** to choose up to 6 soldiers
  (your choice is saved).
- **Campaign** plays the next of 8 missions, each on its own fixed map with a bigger or nastier robot garrison
  (snipers, then grenadiers, then rockets, then everything). Win a mission to unlock the next; after a win,
  **Next mission** carries straight on. The **Missions** tab lists them all, and won missions can be replayed.
- **Skirmish** is a one-off battle on a random map.
- **Pause** (or P / Space on desktop) freezes the battle; you can still pan, select and give orders, which
  play out on **Resume**. The game also pauses itself if the app goes to the background.
- In battle, **Menu** (or the back button / Esc) returns home; after a battle pick **Play again** or **Squad**.
- Tap one of your soldiers to select it (tap again to deselect).
- Select a group: double-tap a soldier or press **All** for the whole squad, or hold a finger still
  for a moment and then drag a box.
- Tap the ground to move — a group spreads into formation. The **FIGHT / RUN** button picks the kind of move:
  **FIGHT** (default) is an attack-move, shooting at anything in reach on the way (at 70% speed while
  firing); **RUN** ignores enemies until arrival, for retreating. Idle soldiers hold their spot and step
  back to it if shoved.
- Tap an enemy to attack it with everything selected. Idle soldiers auto-fire at enemies they can see in range.
- Rocks, crates and trees block movement and line of sight; soldiers path around them.
- A soldier hugging an obstacle that sits between it and a shooter is in cover: most bullets spark off it.
- Drag to pan, pinch (or mouse wheel) to zoom.
- Soldier types (both sides field them; badge letter over the health bar):
  - **Rifleman** — steady all-rounder.
  - **S — Sniper** — long range, heavy single shots that mostly ignore cover; fragile.
  - **G — Grenadier** — lobs grenades over walls at anything a squadmate can see; area damage ignores cover.
  - **R — Rocketeer** — slow, dodgeable rockets with a big blast; still splashes targets hiding behind cover.
  - **Sc — Scout** (yours only) — fast and fragile with a light pistol and a 170° view; spots targets for grenadiers from
    much farther away than anyone else.
- Soldiers only see forward: each type has a vision cone (scout 170°, rifleman/grenadier 120°, rocketeer 100°, sniper 70°)
  and only notices enemies inside it (or within arm's reach). A soldier that's shot turns toward the shooter.
  Robots look where they walk and scan while pausing on patrol — their faint red cones show where they're
  looking, so you can flank them.
- Robots fight smart: once one spots you it calls nearby robots in, and each runs to cover that shields it from
  its target while still giving it a shot, so flank them or bring grenades and rockets to flush them out.
- Selected soldiers highlight their field of fire: their forward cone within weapon range,
  with areas hidden behind obstacles cut out. Grenadiers also show their lob range: a lighter full circle
  with a dashed edge that carries on over obstacles, since they can lob over them at anything a squadmate spots.
- Lines of fire show who is shooting whom (green: yours, red: robots), flaring on each shot. A greyed line
  ending in a bar means the target's cover is stopping most of those shots; a dashed arc is a grenade lob.
- Destroy every robot to win; lose your whole squad and it's over.

## Build
Requires the Android SDK at `~/Android/Sdk` (see `local.properties`) and JDK 21
(`org.gradle.java.home` in `gradle.properties`).

    ./gradlew :desktop:run              # play on desktop
    ./gradlew :android:assembleDebug    # APK -> android/build/outputs/apk/debug/
    ./gradlew :android:installDebug     # install on a USB-connected phone
    ./gradlew :core:test                # headless battle simulation tests

## Layout
- `core/` — game code: `Battle` (simulation/AI), `Terrain` (map, obstacles, A*, line of sight, cover), `Soldier`, `Squad` (saved loadout/record), `Campaign` (missions and progress), `HomeScreen`, `GameScreen` (render + input)
- `desktop/`, `android/` — platform launchers
- `assets/` — sprites from Kenney's Top-down Shooter pack (CC0); fonts Black Ops One and Rajdhani (SIL OFL, `assets/fonts`)
