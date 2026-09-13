# CI Failure Diagnosis Guide

## MINEHOST-SPECIFIC PATTERNS

### Known gradle-wrapper.jar Validation Failure (INFRA)

**Symptom:** CI fails at "Set up Gradle" step with gradle-wrapper.jar validation error.

**Root Cause:** Baseline CI infrastructure issue with gradle-wrapper.jar checksum validation.

**Action:** This is a known INFRA failure. Autonomous repair is authorized once CI evidence confirms the root cause. Review CI logs for specific validation error. If confirmed as gradle-wrapper.jar issue, repair is permitted with smallest safe change only. Retry limits remain hard—do not loop indefinitely.

**Do NOT:** Modify gradle-wrapper.jar directly without documented evidence of the specific defect.

---

### Protected Systems (Non-Negotiables)

The following systems are **frozen by design** and may only be modified with documented evidence of a concrete defect:

1. **JVM Launcher** — Native JVM invocation, runtime setup, process management
2. **Runtime Extraction & Validation** — Runtime blob extraction, checksum validation, integrity checks
3. **CMake Configuration** — Build system config, native toolchain setup
4. **Engine Launch Commands** — Server startup sequences, argument passing, environment setup
5. **Engine Download System** — Remote fetch, install, versioning, asset management

**Principle:** If modifying any of these, you must:
- Provide documented evidence of a concrete defect (CI log, error trace, or reproducible case)
- Make the smallest safe change that fixes only that defect
- Avoid scope creep or "while we're here" refactors

---

### Constraints from CLAUDE.md

- **No local verification gates:** This Termux device has NO JDK/Gradle/adb. Local compile/test/device verification is UNAVAILABLE.
- **Rely on CI:** All verification depends on CI. Mark unverified work as `UNVERIFIED` without real device evidence.
- **Push via gated tool:** Use `./tools/push-gated.sh` for all pushes. Never force-push. Monitor with `./tools/ci-watch.sh`.
- **Env limitations:** ripgrep unavailable on arm64-android (Glob/Grep tools error); use `grep` or Bash. `jq` missing; use `gh --jq` or python3.

---

## EXAMPLE SCENARIOS

### Scenario 1: Compile Error (Your Code)

**CI Output:**
```
[COMPILE ERROR] src/main/java/com/example/Server.java:42: error: cannot find symbol
  symbol:   method loadConfig()
  location: class ServerConfig
```

**Diagnosis:**
- Error is in application code (not gradle, not runtime, not infra).
- Method `loadConfig()` is called but not defined in `ServerConfig` class.

**Fix:**
- Add the missing method to `ServerConfig`, OR
- Correct the call site to use the correct method name.
- Verify the fix in a local editor, then push via `./tools/push-gated.sh` and monitor CI.

**Outcome:** CI passes compile step.

---

### Scenario 2: Test Failure (Your Code/Test)

**CI Output:**
```
[TEST FAILURE] ServerBootstrapTest.java:67
Expected: <true>
Actual:   <false>
at com.example.ServerBootstrapTest.testInitialization(ServerBootstrapTest.java:67)
```

**Diagnosis:**
- Test assertion failed in application test code.
- Server initialization is not returning the expected state.

**Fix:**
- Review the test expectation vs. actual initialization logic.
- Either fix the implementation to meet the test requirement, OR
- Fix the test if the requirement changed.
- Push via `./tools/push-gated.sh`.

**Outcome:** Test passes; CI progresses.

---

### Scenario 3: gradle-wrapper.jar Validation Failure (Known INFRA)

**CI Output:**
```
Set up Gradle
ERROR: gradle-wrapper.jar validation failed: checksum mismatch
Expected: abc123def456...
Actual:   xyz789uvw012...
```

**Diagnosis:**
- This is a known baseline CI failure (INFRA).
- gradle-wrapper.jar checksum validation is failing in the CI environment.

**Action:**
- Autonomous repair is authorized once this root cause is confirmed in CI logs.
- Investigate whether gradle-wrapper.jar was corrupted, or checksum is out of sync.
- Apply smallest safe fix (e.g., re-download gradle-wrapper.jar with correct checksum, or update the expected checksum).
- Do NOT modify gradle-wrapper.jar without documented evidence.

**Monitor:** Push via `./tools/push-gated.sh` and watch CI with `./tools/ci-watch.sh`.

**Outcome:** CI passes gradle setup; build proceeds.

---

### Scenario 4: Network Timeout (INFRA)

**CI Output:**
```
[DOWNLOAD ERROR] Downloading engine artifact from https://releases.example.com/engine-1.0.zip
Timeout: connection refused after 30s
Retrying... (attempt 2 of 3)
```

**Diagnosis:**
- Infrastructure/network issue, not application code.
- Remote artifact server is unreachable or slow.

**Action:**
- This is an INFRA issue; do NOT modify application code.
- Check if remote server is available (ping, curl, etc.).
- If transient, CI retry logic will handle it (attempt 2/3).
- If persistent, escalate to infrastructure team or retry manually with `./tools/ci-watch.sh`.

**Outcome:** CI passes on retry, or manual retry resolves it.

---

### Scenario 5: Protected System Modification Detected

**CI Output:**
```
[PROTECTED SYSTEM CHANGE] src/native/jvm_launcher.c
Modified file in protected system: JVM Launcher
No documented defect provided. Push rejected.
```

**Diagnosis:**
- CI gate detected a change to a protected system (JVM launcher, runtime validation, CMake, engine launch, engine download).
- No documented evidence of a concrete defect was provided in the commit message or PR description.

**Action:**
- Review the change: is it necessary to fix a specific defect?
- If YES: Document the concrete defect in the commit message or PR description (reference CI log, error trace, or reproducible case).
- If NO: Revert the change. Protected systems are frozen by design.
- Re-push via `./tools/push-gated.sh`.

**Outcome:** CI passes; protected systems remain stable.

---

## KEY PRINCIPLES

1. **Separate Concerns:** Distinguish between application code failures (your responsibility), test failures (your responsibility), infrastructure failures (CI/environment), and protected system violations (non-negotiable constraints).

2. **INFRA vs. Code:** If CI fails in a setup step (gradle, download, environment) before reaching your code, it's INFRA. If it fails during compile, test, or runtime of your code, it's your responsibility.

3. **Protected Systems Are Frozen:** JVM launcher, runtime validation, CMake, engine launch, and engine download are only modifiable with documented evidence of a concrete defect and smallest safe change.

4. **No Local Verification:** This device has no JDK/Gradle/adb. Rely entirely on CI. Mark work `UNVERIFIED` without real device evidence.

5. **Use Gated Push:** Always push via `./tools/push-gated.sh`. Monitor CI with `./tools/ci-watch.sh`. Never force-push.

6. **Autonomous Repair:** Known baseline failures (gradle-wrapper.jar validation) can be autonomously repaired once root cause is confirmed in CI. Retry limits are hard.

7. **Smallest Safe Change:** For any modification, especially to protected systems, make the minimal change that fixes the specific documented defect. Avoid refactors or scope creep.

8. **Document Everything:** Commit messages and PR descriptions must explain the defect, the evidence, and why the change was necessary. This creates accountability and enables review.
