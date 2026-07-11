ClusterTune BoostFramework probe v4
===================================

WHERE WE ARE:
  v3 log was a big step: hidden-API bypass worked, BoostFramework
  constructed, perfLockAcquire(int,int[]) returned VALID handles
  (5422+) with NO SELinux denial. But scaling_max_freq didn't move
  with opcode 0x40804200.

  Likely reason: perflock frequency caps are applied in the perf HAL /
  governor layer and often DON'T write scaling_max_freq. The cap shows
  up in scaling_CUR_freq under load, not in the static max. OR the
  opcode is simply wrong for this build.

v4 DOES:
  - Dumps every BoostFramework field that looks like a freq/cluster
    opcode (FIELD <name> = 0x...), so we can use the device's OWN
    constants instead of guessing.
  - Tries a RANGE of candidate opcodes.
  - After each acquire, reads ALL policies' scaling_max_freq AND
    scaling_cur_freq, plus cpu_max_freq.
  - Spins CPU load during measurement so cur_freq is meaningful.

HOW TO TEST (same as before):
    cd C:\platform-tools
    .\adb logcat -c
  Open ClusterTune, wait ~15s (it runs several opcode/value combos), then:
    .\adb logcat -d | Select-String -Pattern "ClusterTuneBoost" | Out-File -Encoding utf8 boost-probe4.log
  Upload boost-probe4.log.

WHAT TO LOOK FOR:
  - "FIELD ..._MAX_FREQ_CLUSTER_... = 0x...." lines -> the REAL opcodes.
    Even if nothing moves, these tell us the exact constants to use.
  - Any line where curUnderLoad for p7 drops well below baseline while
    a cap is held -> the cap WORKS (just not via scaling_max_freq).
  - Any change in max[...] or cpu_max -> cap writes a sysnode directly.

HONEST NOTE: even if this works, a perflock is a TEMPORARY cap (held
while the lock is alive). Turning it into a persistent underclock needs
a long-held lock + a background service to re-acquire it. Heavier than
writing scaling_max_freq, but still no-root / no-Settings-detour. We'll
weigh that against the Odin Settings handoff once we know it caps.

Reminder: OdinHandoffDialog.kt must NOT be in your fork (0.3.1 leftover
that breaks the build). Absent from this tree.
