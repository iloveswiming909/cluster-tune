# Execution methods plan

Goal: make ClusterTune usable on more Android handhelds by separating “how privileged commands run” from the tuning code that needs privileged reads/writes.

## Problem

Current code assumes one privileged path:

```text
ClusterTune -> RootCommandRunner / PServerSysfsReader -> PServerBinder -> stdout
```

That is too narrow. Some devices may expose `PServerBinder` but not return stdout reliably. Others may only work with `su`; Shizuku may be viable later for binder/shell-backed operations but should not block the first refactor.

## Target architecture

Introduce a small common API:

```kotlin
interface PrivilegedExecutionMethod {
    val id: String
    fun probe(): ExecutionProbeResult
    fun executeScript(scriptName: String, scriptContents: String): Result<String?>
    fun readText(path: String): String?
}
```

Everything above this layer should treat privileged execution as a capability, not as “PServer”. Existing call sites should continue to ask for:

- `isAvailable`
- `executeScript(...)`
- protected sysfs `readText(path)`

## Method order

1. **PServer stdout**
   - Probe: `PServerBinder` exists and `echo <marker>` returns the marker.
   - Best path. Existing behavior, but explicitly capability-checked.

2. **PServer file-output fallback**
   - Probe: `PServerBinder` exists, command execution works, stdout does not work, but the command can write to an app-owned output file readable by ClusterTune.
   - Use for devices where PServer executes commands but returns empty/null output.
   - `executeScript` runs the script with stdout/stderr redirected to an intermediary file.
   - `readText(path)` runs `cat path > intermediary-file`, then reads the intermediary file from app storage.

3. **Root shell (`su`)**
   - Probe: `su -c 'echo <marker>'` returns the marker.
   - Useful on rooted devices without PServer.
   - First implementation can be conservative and not lock files aggressively until tested.

4. **Shizuku**
   - Implemented behind the same API.
   - Probe: Shizuku binder alive, app permission granted, and `echo <marker>` returns stdout.
   - On-device test tomorrow should decide whether Shizuku has enough privilege for ClusterTune's sysfs reads/writes. If it does not, remove this method and dependency.

## Implementation milestones

### Milestone 1: abstraction only

- Add `PrivilegedExecutionMethod` and `ExecutionProbeResult`.
- Add resolver/selector with ordered methods.
- Implement PServer stdout method.
- Implement PServer file-output fallback method.
- Wire `RootCommandRunner` and `PServerSysfsReader` through the selected method while keeping their public APIs stable.
- Unit-test method selection with fakes.

### Milestone 2: root shell fallback

- [x] Add `RootShellExecutionMethod`.
- [x] Use `ProcessBuilder("su", "-c", command)` with bounded output capture.
- [ ] On-device verify before treating it as supported in UI.

### Milestone 3: Shizuku implementation

- [x] Add Shizuku API/provider dependencies and manifest provider.
- [x] Add `ShizukuExecutionMethod` behind the same API.
- [x] Probe binder availability, permission, and actual shell stdout using `echo <marker>`.
- [ ] On-device verify whether Shizuku's shell/root identity can read/write the sysfs nodes ClusterTune needs.
- [ ] If Shizuku cannot access the required nodes, remove the method and dependency.

### Milestone 4: capability reporting

Expose diagnostics in state/UI/logs:

```text
selected method: pserver-stdout | pserver-file-output | root-shell | unavailable
pserver present: yes/no
stdout supported: yes/no
file-output fallback: yes/no
root shell: yes/no
last probe failure: ...
```

This matters because device support bugs will otherwise be opaque.

### Milestone 4: Shizuku spike

- Check whether Shizuku can run the exact sysfs reads/writes ClusterTune needs on target devices.
- If yes, add `ShizukuExecutionMethod` behind the same API.
- If no, document why and keep it out of the runtime path.

## Safety constraints

- Keep command escaping centralized.
- Intermediary files must live under `context.filesDir/root-output` and be overwritten per command.
- Do not trust stdout as proof of success for writes; keep readback verification.
- Resolver should cache the selected method for normal use, but there should be a way to force reprobe later.
- Do not add Shizuku dependency until there is evidence it provides the needed privilege level.

## First code slice

Implement Milestone 1, then run:

```bash
./gradlew testDebugUnitTest
```

Expected outcome: existing behavior unchanged on devices with stdout-capable PServer, plus a fallback path for PServer implementations that execute commands but return no stdout.
