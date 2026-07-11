ClusterTune BoostFramework probe build (v3 - hidden-API bypass)
===============================================================

STORY SO FAR:
  - PServer is SELinux-blocked for untrusted_app (hard wall, dead).
  - vendor.perfservice / android.util.BoostFramework is NOT SELinux-
    blocked (boost-avc logs were empty).
  - BUT the class is @hide, so on Android 13 its members are filtered
    from third-party reflection: declaredConstructors returned 0.
    That's Android's hidden-API enforcement, NOT SELinux.

THIS BUILD adds LSPosed's AndroidHiddenApiBypass library and calls
HiddenApiBypass.addHiddenApiExemptions("L") before reflecting. That
un-hides the class members so we can construct BoostFramework and call
perfLockAcquire. Robust on Android 10+.

NEW DEPENDENCY (resolves on GitHub's runners, not locally):
  org.lsposed.hiddenapibypass:hiddenapibypass:4.3
Plus a dependenciesInfo{} block in android{} (required by the lib).

BUILD FILES CHANGED vs the maintainer's 0.3.2 beta:
  - app/build.gradle.kts  (dep + dependenciesInfo)
  - app/src/main/java/com/aure/clustertune/root/BoostFrameworkProbe.kt
  - (plus the earlier pserver-noout WIP, inert on the Mini)
  Reminder: make sure OdinHandoffDialog.kt is NOT in your fork (it's a
  0.3.1 leftover that breaks the build). It's absent from this tree.

HOW TO TEST:
  1. Build via GitHub Actions.
  2. Sideload.
  3. On PC:
       cd C:\platform-tools
       .\adb logcat -c
     Open ClusterTune, wait ~10s, then:
       .\adb logcat -d | Select-String -Pattern "ClusterTuneBoost" | Out-File -Encoding utf8 boost-probe3.log
       .\adb logcat -d | Select-String -Pattern "avc.*perf" | Out-File -Encoding utf8 boost-avc3.log
  4. Upload both.

WHAT WE'LL LEARN:
  - "hidden-API exemption added: ..." shows the unlock ran.
  - "Class ... has N constructor(s)" with N>0 -> unlock worked.
  - "ctor(...): SUCCESS" + "perfLockAcquire found" -> constructed it.
  - TRY lines: look for *** CHANGED *** meaning an opcode/value actually
    moved scaling_max_freq. THAT is the win: a no-root, no-PServer,
    no-Settings-detour write path.
  - If constructors still 0 after exemption, we escalate to the lib's
    explicit HiddenApiBypass.getDeclaredMethods()/newInstance helpers.
