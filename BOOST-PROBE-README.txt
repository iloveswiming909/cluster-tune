ClusterTune BoostFramework probe build (v2 - exhaustive constructor)
====================================================================

STATUS: The first probe found android.util.BoostFramework exists and
there was NO SELinux denial (boost-avc.log was empty) — so untrusted_app
is NOT blocked from this path. We just couldn't hit the right
constructor signature.

This v2 probe enumerates ALL of BoostFramework's constructors, tries
each with plausible args (Context/false/null), and if it still can't
construct, DUMPS every constructor signature and perfLock*/perfHint*
method so we know exactly what the real API looks like.

HOW TO TEST (same as before):
  1. Build via GitHub Actions.
  2. Sideload.
  3. On PC:
       cd C:\platform-tools
       .\adb logcat -c
     Open ClusterTune, wait ~10s, then:
       .\adb logcat -d | Select-String -Pattern "ClusterTuneBoost" | Out-File -Encoding utf8 boost-probe2.log
       .\adb logcat -d | Select-String -Pattern "avc.*perf" | Out-File -Encoding utf8 boost-avc2.log
  4. Upload both.

WHAT WE'LL LEARN:
  - "ctor(...): SUCCESS" then TRY lines -> we constructed it; the TRY
    lines show whether any opcode/value moved scaling_max_freq
    (look for *** CHANGED ***).
  - Still can't construct -> the "available ctor:" and "available
    method:" dumps tell us the exact signatures, and the next build
    will target them precisely.

Either outcome moves us forward.
