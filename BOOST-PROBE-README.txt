ClusterTune BoostFramework probe v5 - focused hold-and-measure
==============================================================

WHY: v4 showed a FAINT signal that big-cluster opcode 0x40804000 held
policy3's cur_freq down (1920000 vs 2323200), but it was too noisy to
trust - locks were released between samples, load was one unpinned
thread, single samples.

v5 removes every confounder:
  Phase A: ALL cores loaded, NO lock  -> record PEAK cur_freq/cluster.
  Phase B: hold ONE big-cluster perflock, keep load -> PEAK again.
  Phase C: release lock, keep load    -> PEAK again (recovery check).
Samples ~every 25ms and reports the peak, so DVFS jitter averages out.
Tries big-cluster opcodes {0x40804000, 0x40804200, 0x40804100} with
both kHz and index-style target values.

VERDICT LINES tell the story directly:
  "VERDICT op=0x... target=...: p3 A=<peak> B=<peak> C=<peak> -> ..."
  - "*** CAP WORKS ***" means B peak was >=100MHz below A peak
    (i.e. the lock actually capped the big cluster), and "+recovered"
    means C climbed back.
  - "no cap" for all combos means perflock does NOT cap CPU freq on this
    firmware from an app -> this path is dead, and we go to the handoff.

NOTE: the device will run all cores flat-out for ~5-35s (fans will spin,
it'll warm up). That's the load generator; it stops when the test ends.

HOW TO TEST:
    cd C:\platform-tools
    .\adb logcat -c
  Open ClusterTune, wait ~40s (let it finish all phases), then:
    .\adb logcat -d | Select-String -Pattern "ClusterTuneBoost" | Out-File -Encoding utf8 boost-probe5.log
  Upload boost-probe5.log.

Reminder: OdinHandoffDialog.kt must NOT be in your fork.
