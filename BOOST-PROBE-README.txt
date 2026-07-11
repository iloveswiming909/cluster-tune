ClusterTune BoostFramework probe build
======================================

WHY: PServer is SELinux-blocked for untrusted_app on the Odin 2 Mini
(confirmed: avc denied untrusted_app -> pservice binder call, enforcing).
So no PServer-based method can ever work in a normal app.

This build tests the last remaining candidate: Qualcomm's BoostFramework
/ vendor.perfservice (com.qualcomm.qti.IPerfManager), which runs as the
`system` user and CAN write the cpufreq max-freq sysnodes. It's the API
games use for perf boosts, so untrusted_app may be allowed to call it.

The documented MPCTLV3 opcodes set per-cluster MAX frequency (a downward
cap), which is exactly what we need:
  prime cluster (policy7): 0x40804200

WHAT IT DOES: On every app launch, a background probe (tag
"ClusterTuneBoost") constructs BoostFramework, reads policy7
scaling_max_freq, then calls perfLockAcquire with the prime max-freq
opcode and several candidate value encodings, reading the frequency back
after each to see if it moved.

HOW TO TEST:
  1. Build this via your GitHub Actions (same as before). Remember to
     DELETE app/src/main/java/com/aure/clustertune/ui/OdinHandoffDialog.kt
     from your fork if it's still there (it's already absent from this
     tree).
  2. Sideload the APK.
  3. On PC:
       cd C:\platform-tools
       .\adb logcat -c
     Open ClusterTune, wait ~10 seconds, then:
       .\adb logcat -d | Select-String -Pattern "ClusterTuneBoost" | Out-File -Encoding utf8 boost-probe.log
  4. Upload boost-probe.log.

WHAT THE LOG WILL TELL US:
  - "Could not construct BoostFramework" -> class missing/blocked; dead end.
  - "BoostFramework constructed OK" + no SELinux denial -> callable!
  - "*** CHANGED ***" on any TRY line -> that opcode+value moved the
    frequency. We win: a no-root, no-PServer write path exists, and we
    know the exact encoding to use.
  - All "(no change)" -> callable but these opcodes/values don't cap on
    this firmware; may need different opcodes or perfservice may refuse
    downward caps from an app.

Also worth grabbing alongside (shows any SELinux denial for perfservice):
       .\adb logcat -d | Select-String -Pattern "avc.*perf" | Out-File -Encoding utf8 boost-avc.log

NOTE: This build still contains the pserver-noout method and other WIP,
but those are inert on the Mini (SELinux-blocked). Only the BoostFramework
probe matters for this test.
