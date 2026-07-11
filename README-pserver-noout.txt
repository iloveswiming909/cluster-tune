ClusterTune — Odin 2 Mini fix via PServer fire-and-forget ("pserver-noout")
============================================================================

THE ROOT CAUSE (finally pinned down)
------------------------------------
Decompiling Odin's own Settings app (com.odin2.common.PServiceBridgeV2 +
ExecuteScriptTask) revealed exactly how AYN writes to protected sysfs
nodes on the Mini, and why ClusterTune's PServer path failed.

PServer's suCmd takes a flag: "1" = capture stdout, "0" = fire-and-forget.

  - Odin Settings uses flag "0" for every WRITE it does (chmod, echo >
    sysfs, cp, dd, etc). Confirmed across dozens of call sites.
  - Odin Settings uses flag "1" only when it needs to READ output back
    (cat, mount, dumpsys).

On the Mini, PServer's stdout-capture path ("1") is broken: it returns
empty AND the command may never execute. ClusterTune was sending its
apply script with flag "1" (and as a single `sh script.sh` call), so the
write silently never happened.

The Odin script-runner works because it sends each line with flag "0".
That runs as full root (uid=0, u:r:pservice:s0, all caps) — verified on
device.

THE FIX
-------
A new execution method, "pserver-noout" (PServer write-only):
  - Runs apply commands one line at a time, each fire-and-forget (flag 0),
    exactly like Odin's ExecuteScriptTask.
  - Probes by writing a marker file (flag 0) and reading it back via the
    File API — so it correctly reports "available" on the Mini despite
    broken stdout.
  - Reads sysfs values via direct File I/O (cpufreq nodes are
    world-readable).

It's inserted right after "pserver-stdout" in the auto-detection order, so
healthy devices (Odin 3 etc.) are completely unaffected — their stdout
probe passes first and they never touch the new method.

FILES CHANGED
-------------
NEW  app/src/main/java/com/aure/clustertune/root/PServerFireAndForgetExecutionMethod.kt
MOD  app/src/main/java/com/aure/clustertune/root/RootExec.kt
       (added captureStdout flag to executeAsRoot; interface + impl)
MOD  app/src/main/java/com/aure/clustertune/root/PrivilegedExecutionMethod.kt
       (registered pserver-noout in resolver + auto-detect order)
MOD  app/src/main/java/com/aure/clustertune/ui/SettingsScreen.kt
       (added method info card; adjusted take(3)/drop(3) layout split)
MOD  app/src/main/java/com/aure/clustertune/ui/TunerViewModel.kt
       (added display label for pserver-noout)

IMPORTANT — AFTER INSTALLING
----------------------------
If you're upgrading over the earlier beta, it may have saved a previous
execution method choice (e.g. "shizuku"). Because Shizuku's probe still
passes, the app could keep using it and apply would still fail.

Do ONE of these after installing:
  a) Settings -> scroll to the execution method section -> tap "Auto
     detect".  It will now find and select "PServer (write-only)".
  OR
  b) Manually pick "PServer (write-only)" in that same section.
  OR
  c) Clear the app's data before first launch (forces fresh auto-detect).

Then apply a preset. The underclock should land. Verify with:
  adb shell "cat /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq"

HOW TO BUILD
------------
Replace the corresponding files in your fork (or drop in the whole
ClusterTune-0.3.2-beta.1-pserver-noout tree), push, and let the Debug
build workflow produce the APK. The one new file needs "Add file ->
Create new file" on GitHub (type the full path). The rest are edits to
existing files.
