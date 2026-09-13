# CI Failure Diagnosis Guide

This guide provides a systematic approach to diagnosing and fixing GitHub Actions CI failures. Follow this when `ci-watch.sh` returns exit code 1.

---

## WHEN TO USE THIS

Use this guide when:
- GitHub Actions CI workflow completes with a failure status
- `ci-watch.sh` returns exit code 1
- A build, test, or validation step fails in the pipeline
- You need to triage whether the failure is code-related or infrastructure-related

This is NOT for:
- Local development issues (use standard debugging)
- Pre-commit failures (check git hooks)
- Uncommitted changes blocking CI (commit first)

---

## STEP 1: READ LOGS CAREFULLY

### Immediate Actions

1. **Open the failed workflow run** in GitHub Actions
2. **Identify which step failed** - look for the red X marker
3. **Read the full log output** - scroll past truncated summaries to raw output
4. **Extract the actual error message** - look for:
   - Stack traces (starting with `Exception`, `Error:`, `FAILED`)
   - Compiler errors (file path + line number)
   - Test assertion failures (expected vs actual)
   - Network errors (connection refused, timeout, DNS)
   - Permission errors (access denied, unauthorized)

### Log Reading Checklist

- [ ] Identified the failing step name
- [ ] Located the first error or failure line
- [ ] Found any error codes or exit codes
- [ ] Checked for timestamp markers (failure time relative to start)
- [ ] Searched for "error", "exception", "failed", "timeout" (case-insensitive)
- [ ] Noted any warnings that preceded the failure

### Common Log Patterns

```
# Compile error - look for file:line format
src/main/java/Example.java:42: error: cannot find symbol

# Test failure - look for assertion messages
AssertionError: expected <5> but was <3>

# Build system error - gradle/cmake issues
Could not resolve dependency: com.example:library:1.0.0

# Network error - connection issues
Connection refused: tcp://docker.io:443
timeout waiting for connection pool

# Dependency conflict - version mismatch
Multiple versions of dependency found: 1.0.0, 2.0.0
```

---

## STEP 2: CLASSIFY THE FAILURE

### Five Failure Categories

#### COMPILE ERROR
**Symptoms:**
- Error message contains filename:line format
- Message mentions "cannot find symbol", "type error", "syntax error"
- Java: `.java:` with error code
- Kotlin: similar format
- Build fails during compilation phase

**Root Causes:**
- Syntax errors in source code
- Type mismatches
- Missing imports
- Undefined variables or methods
- Incorrect annotations

**Example:**
```
src/main/java/Server.java:15: error: cannot find symbol
  symbol: class RequestHandler
```

---

#### TEST FAILURE
**Symptoms:**
- Test runner output visible (JUnit, TestNG, Gradle test task)
- Specific test name listed with FAILED status
- Assertion message shows expected vs actual
- Test execution completes, but assertions fail

**Root Causes:**
- Code logic bug (incorrect calculation, wrong condition)
- Test data issue (missing fixtures, invalid setup)
- Timing issue (race condition, timeout)
- Environment dependency (missing config, database state)

**Example:**
```
FAILED com.example.CalculatorTest::testAddition
AssertionError: expected <10> but was <15>
```

---

#### BUILD SYSTEM ERROR
**Symptoms:**
- Error in Gradle, CMake, Maven, or build tool
- Message mentions "build failed", "task failed"
- Dependency resolution errors
- Plugin initialization failures
- Configuration errors

**Root Causes:**
- Invalid build.gradle.kts syntax
- Missing Gradle plugins or incorrect versions
- Gradle property typos or incorrect types
- Conflicting plugin versions
- Missing build dependencies

**Example:**
```
Build file 'build.gradle.kts' line 42: Script compilation error
  Unrecognized property: compileSdk
```

---

#### INFRA ERROR
**Symptoms:**
- Network-related: "Connection refused", "timeout", "DNS resolution failed"
- Authentication: "401 Unauthorized", "403 Forbidden", "access denied"
- Rate limits: "429 Too Many Requests", "quota exceeded"
- External service: "Service unavailable", "502 Bad Gateway"
- Container/Docker: "image not found", "registry unreachable"

**Root Causes:**
- Network connectivity issue (CI runner to external service)
- GitHub API rate limits
- Missing GitHub secrets or incorrect values
- Docker registry authentication failure
- External service downtime
- Firewall/networking restrictions in CI environment

**Example:**
```
ERROR: failed to solve with frontend dockerfile.v0: failed to pull image 
"ubuntu:latest": error pinging registry: Get "https://registry-1.docker.io": 
dial tcp: i/o timeout
```

---

#### DEPENDENCY ERROR
**Symptoms:**
- "Could not resolve dependency"
- "Version conflict" or "multiple versions found"
- "Cannot find package"
- Gradle resolution error with artifact coordinates
- Package manager cannot locate library

**Root Causes:**
- Typo in dependency coordinates (group, artifact, version)
- Repository misconfiguration
- Version doesn't exist in repository
- Two dependencies require incompatible versions
- Repository access denied or offline
- Transitive dependency mismatch

**Example:**
```
Could not resolve dependency: com.google.guava:guava:99.0-invalid
Caused by: org.gradle.api.GradleException: 
  Could not find com.google.guava:guava:99.0-invalid.
```

---

## STEP 3: DECISION TREE

Use this flowchart to quickly classify and locate the problem:

```
Does the log contain compiler/syntax errors?
├─ YES → COMPILE ERROR (STEP 2.1)
│  └─ Fix source code, verify syntax
├─ NO → Continue

Does the error show a test runner failure (FAILED test)?
├─ YES → TEST FAILURE (STEP 2.2)
│  └─ Fix test or code logic
├─ NO → Continue

Does the error mention Gradle, CMake, build config, or plugins?
├─ YES → BUILD SYSTEM ERROR (STEP 2.3)
│  └─ Fix build configuration
├─ NO → Continue

Does the error mention network, auth, timeout, or external service?
├─ YES → INFRA ERROR (STEP 2.4)
│  └─ Check network/secrets/external services
├─ NO → Continue

Does the error mention dependency resolution, version conflict, or missing package?
├─ YES → DEPENDENCY ERROR (STEP 2.5)
│  └─ Fix dependency coordinates or versions
├─ NO → Continue

Unable to classify
└─ Search logs for: error, exception, failed, timeout, denied
   Try searching GitHub Issues for the exact error message
```

---

## STEP 4: DETERMINE RESPONSIBILITY

### Is This My Code or Infrastructure?

Ask these questions:

**1. Did this fail locally?**
- Compile your code locally with the same Gradle version
- Run tests locally
- If it also fails locally → **YOUR CODE** (fix it)
- If it passes locally → **LIKELY INFRA** (but verify environment)

**2. Did it pass yesterday?**
- Check git history - did you change this file?
- Did you change build.gradle.kts or dependencies?
- If no changes to affected code → **LIKELY INFRA** (rollback infrastructure)
- If you changed it → **YOUR CODE** (review your changes)

**3. Is it a known intermittent failure?**
- Check if it's a timing/race condition (tests pass sometimes)
- Check if it's a network flake (infrastructure issue)
- If intermittent with no code change → **INFRA ISSUE** (add retry logic or investigate network)
- If consistent → **YOUR CODE ISSUE** (fix deterministically)

**4. Did CI pass for other branches/PRs recently?**
- If main branch is green → **YOUR BRANCH** (your code broke it)
- If main branch is also red → **SHARED INFRA ISSUE** (affects everyone)

### Responsibility Matrix

| Category | My Code | Infrastructure |
|----------|---------|-----------------|
| COMPILE ERROR | Always | Never |
| TEST FAILURE | Usually | Rarely (env-dependent tests) |
| BUILD SYSTEM ERROR | Usually | Rarely (Gradle/plugin versions) |
| INFRA ERROR | Never | Always |
| DEPENDENCY ERROR | Often | Sometimes (repo access) |

---

## STEP 5: IMPLEMENT THE FIX

### For COMPILE ERROR

1. Open the file mentioned in the error
2. Go to the line number specified
3. Read the error message carefully - it tells you exactly what's wrong
4. Fix the syntax or type error
5. Verify locally: `gradle build`
6. Commit and push

Example fixes:
```java
// Before - cannot find symbol
RequestHandler handler = new RequestHandlerr();  // typo

// After
RequestHandler handler = new RequestHandler();
```

---

### For TEST FAILURE

1. Identify the failing test class and method
2. Run locally: `gradle test --tests com.example.CalculatorTest.testAddition`
3. Read the assertion failure - what was expected vs actual?
4. Fix the code logic or test setup
5. Run the test again locally until it passes
6. Verify no other tests broke: `gradle test`
7. Commit and push

Example debugging:
```java
// Test shows: expected <10> but was <15>
// Review the code:
int result = calculator.add(5, 10);  // Returns 15 instead of 10
// Bug: add() is actually multiplying!
return a * b;  // Should be: return a + b;
```

---

### For BUILD SYSTEM ERROR

1. Review your recent changes to `build.gradle.kts`
2. Check syntax - compare to working version in git
3. Verify all property names are correct (case-sensitive)
4. Validate dependency coordinates exist
5. Run locally: `gradle clean build --stacktrace`
6. Fix the configuration error
7. Test locally again
8. Commit and push

Example fixes:
```kotlin
// Before - typo in property name
android {
    compileSdk = 34
    targetSdk = 34  // exists
    minSdk = 24
}

// After - if minSdk is invalid
android {
    compileSdk = 34
    targetSdk = 34
    minSdk = 21  // Changed to valid value
}
```

---

### For INFRA ERROR

1. Verify the issue is real (not flaky) - rerun the workflow
2. If network error: check external service status
3. If auth error: verify GitHub secrets are set correctly in Settings
4. If rate limit: add exponential backoff retry logic
5. If container image: verify registry is accessible and image tag is correct
6. If not your code: notify infrastructure team or wait for service recovery

Actions:
```yaml
# Example: Add retry logic for network flakes
- name: Fetch dependencies
  run: gradle build
  with:
    retry-times: 3
    timeout-minutes: 10
```

---

### For DEPENDENCY ERROR

1. Verify the dependency coordinates in `build.gradle.kts`
2. Check Maven Central or the official repository for correct version
3. If version doesn't exist, use a version that does
4. If there's a version conflict, align to a compatible version
5. Run locally: `gradle dependencies` to see the dependency tree
6. Fix the coordinates or versions
7. Test locally: `gradle build`
8. Commit and push

Example fixes:
```kotlin
// Before - invalid version
dependencies {
    implementation("com.google.guava:guava:99.0-invalid")  // doesn't exist
}

// After - correct version
dependencies {
    implementation("com.google.guava:guava:33.0-jre")  // actual version
}
```

---

## NEVER DO THIS

### Anti-Patterns to Avoid

❌ **Don't ignore the error message** - Read the full message, not just the summary

❌ **Don't change random code hoping it fixes CI** - Understand the root cause first

❌ **Don't commit to main branch if CI is failing** - Always fix on a branch, verify locally

❌ **Don't update multiple unrelated things** - Fix one issue per commit

❌ **Don't blame "someone else's code" without evidence** - Check git blame and recent changes

❌ **Don't add `|| true` to silence failures** - That hides real problems

❌ **Don't make CI fixes without running locally** - Verify the fix works before pushing

❌ **Don't commit secrets or credentials** - Use GitHub Secrets for sensitive data

❌ **Don't ignore dependency warnings** - They become errors later

❌ **Don't assume the CI environment is different** - It usually isn't; your local environment needs cleanup

---

## ATTEMPT TRACKING

Track your debugging attempts to avoid loops and know when to pivot.

### Attempt Log Template

```
Failure: [Error message summary]
Classification: [COMPILE/TEST/BUILD/INFRA/DEPENDENCY]
Responsibility: [MY CODE / INFRA]

Attempt 1: [What you tried]
  Result: [Outcome - worked/failed/unclear]
  Time: [When - now or duration]

Attempt 2: [What you tried next]
  Result: [Outcome]
  Time: [When]

Attempt 3: [Third approach]
  Result: [Outcome]
  Time: [When]

Attempt 4: [Alternative strategy]
  Result: [Outcome]
  Time: [When]

Attempt 5: [Last resort before escalation]
  Result: [Outcome]
  Time: [When]

Decision: [FIXED / ESCALATE / SKIP]
```

### Maximum Attempts Rule

- **After 2 failed attempts:** Step back and re-classify the failure
- **After 3 failed attempts:** Check if your understanding of the problem is correct
- **After 4 failed attempts:** Consider if this is an infrastructure issue
- **After 5 failed attempts:** Escalate to team lead or infrastructure team

### When to Give Up

Stop and escalate when:
- The failure is clearly infrastructure-related (network, external service, auth)
- The error is outside your codebase (third-party library, build system bug)
- You've made 5 good-faith attempts with different approaches
- The fix would require changes outside this repository (infrastructure, CI config)
- The error message is unclear and cannot be reproduced locally

---

## QUICK REFERENCE

### Common Commands

```bash
# Build locally with full output
gradle clean build --stacktrace

# Run specific test
gradle test --tests com.example.MyTest.myMethod

# See dependency tree
gradle dependencies

# Clean and rebuild
gradle clean assemble

# Force update of dependencies
gradle build --refresh-dependencies

# Check Gradle version
gradle --version
```

### File Locations

- Gradle config: `build.gradle.kts`
- GitHub Actions workflows: `.github/workflows/*.yml`
- CI output logs: GitHub Actions > specific workflow run > step details
- Source code: `src/main/java/` (Java), `src/main/kotlin/` (Kotlin)
- Tests: `src/test/java/`, `src/test/kotlin/`

### Key Indicators

| Indicator | Meaning |
|-----------|---------|
| `:compileJava FAILED` | Compile error - fix syntax |
| `BUILD FAILED` after `FAILED` test name | Test failure - fix logic |
| `Could not resolve dependency` | Dependency error - fix coordinates |
| `dial tcp:` or `timeout` in error | Infra error - not your code |
| `error: cannot find symbol` | Compile error - missing import or typo |
| `AssertionError:` | Test failure - assertion failed |

---

## ESCALATION PATH

If you've followed this guide and cannot resolve the issue:

1. **Document your findings** - create a comment in the PR or issue with:
   - Classification of the failure
   - Steps you've taken
   - Attempt log
   - Local verification results

2. **Tag relevant team** - if INFRA issue, notify @infrastructure-team or equivalent

3. **Check team Slack** - search for this error - someone may have solved it

4. **Search GitHub Issues** - someone may have reported this bug

5. **Ask for help** - post in team channel with:
   - Link to failing workflow
   - Classification from this guide
   - Your attempt log

---

**Last Updated:** 2026-09-13
**Applies to:** Java integration with Gradle build system
**Related:** `ci-watch.sh`, GitHub Actions workflows in `.github/workflows/`
