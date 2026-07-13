# ClusterTune — no-root wireless-debug execution method

Adds a new privileged-execution backend, **"Wireless debug (no root)"**
(`jdwp-inject`), for unrooted Odin 2 / 2 Mini. It applies profiles as
uid=system by injecting into GameAssistant over on-device wireless
debugging — no PC, no root.

## What was added / changed

New:
- `jdwp-injector/` — vendored module from wuyr/jdwp-injector-for-android
  (Apache-2.0; see jdwp-injector/ATTRIBUTION.md + LICENSE-jdwp-injector).
  Provides adb-over-wireless-debugging + JDWP. Includes native SPAKE2
  (CMake + boringssl prefab) for pairing.
- `app/.../jdwp/JdwpInjectionExecutionMethod.kt` — implements the existing
  `PrivilegedExecutionMethod` interface. Runs profile scripts as system by
  injecting `Runtime.exec("sh <script>")` into GameAssistant. Avoids
  wuyr's run-as/dex flow (which fails on a system app).
- `app/.../jdwp/WirelessDebugConnectionManager.kt` — pairing + port
  discovery, holds host/port in memory.
- `app/.../ui/WirelessDebugSetupScreen.kt` — one-time pairing UI
  (6-digit code).

Changed:
- `settings.gradle.kts` — includes `:jdwp-injector`.
- `app/build.gradle.kts` — depends on `project(":jdwp-injector")`.
- `app/.../AppContainer.kt` — creates the connection manager, passes its
  provider into the resolver.
- `app/.../root/PrivilegedExecutionMethod.kt` — registers `jdwp-inject`
  (auto-detection order: tried LAST, so root/PServer win when present).
- `app/.../ui/SettingsScreen.kt` — lists the method + a "Set up wireless
  debugging" button when it's selected.
- `app/.../MainActivity.kt` — shows the setup screen.

## Handoff path
Scripts are staged to `Documents/ClusterScripts/` — the SAME public path
ClusterTune already uses for OdinScriptHandoff (writable by the app with
no runtime permission; readable by GameAssistant, which is in the
external-storage groups).

## Build notes / likely CI snags
- The `jdwp-injector` module builds native code (`src/main/cpp/spake2.cpp`)
  and pulls `io.github.vvb2060.ndk:boringssl:4.0` (prefab). **CI must have
  the Android NDK + CMake.** If the build can't find an NDK, pin
  `ndkVersion` in `jdwp-injector/build.gradle.kts` and ensure the CI
  Android setup installs it.
- minSdk: module=30, app=31 → compatible.

## How to test on device (apply-once model)
1. Build + install ClusterTune.
2. Settings → Execution method → choose **Wireless debug (no root)** →
   **Set up wireless debugging**.
3. Enable Developer options → Wireless debugging. First time: tap
   "Pair device with pairing code", enter the 6-digit code in ClusterTune,
   Pair. Then Connect. Status should show "Connected".
4. Make sure GameAssistant is running (open Game Assistant once).
5. Apply a profile as usual. ClusterTune stages the freq script and
   injects it into GameAssistant, which writes scaling_max_freq as system.
6. Verify the frequencies changed (e.g. via ClusterTune's live values, or
   `cat /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq`).

Note: wireless debugging must be re-paired/re-enabled per boot; the
connection is held in memory only.
