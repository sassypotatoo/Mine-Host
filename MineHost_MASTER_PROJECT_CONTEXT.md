# MineHost — Complete Project Specification, Architecture, Current State, and Roadmap

> **Document purpose:** This is the canonical technical/context document for MineHost. It consolidates the project direction, architecture, runtime design, verified milestones, unfinished work, important decisions, release constraints, and future roadmap discussed across the MineHost development history.
>
> **Current baseline principle:** The latest verified source/build state is authoritative over older plans. Historical ideas are marked as planned, deferred, experimental, or superseded where applicable.
>
> **Project goal:** Build a standalone Android application that can host **real Minecraft server software locally on the phone**, with real processes, real network sockets, real console I/O, persistent worlds, and a path to Play Store publication.

---

# 1. Project Identity

## 1.1 Project name

**MineHost**

Historical/internal names that appeared during development include:

- HostMine
- MineHost Android
- package references such as `com.example.minehost`

The intended end product is **MineHost**, a standalone Android server-hosting application.

## 1.2 Primary product goal

MineHost is intended to turn an Android phone into a local Minecraft server host.

The application is designed around:

- Android as the host operating system
- local server execution on the phone
- real server binaries/JARs
- real TCP/UDP networking
- persistent server data
- live process output
- foreground-service lifecycle management
- optional future tunneling for public access

The product is **not** intended to simulate a server interface.

A server showing `ONLINE`, a console displaying output, or a player list appearing in the UI must correspond to actual server/runtime state.

---

# 2. Core Product Principles

These principles became progressively stricter as MineHost evolved.

## 2.1 Real server execution

The app must execute real server software.

No fake:

- server process
- console log
- player count
- player list
- server status
- world state
- command acknowledgement

## 2.2 Local-first

The server runs on the user's Android device.

Cloud/VPS hosting is not the primary architecture.

The phone is the host.

## 2.3 Standalone Android application

The final product should not require:

- Termux
- PRoot
- a second helper application
- root access

Earlier development experimented with PRoot/Ubuntu-style environments. That direction was ultimately rejected for the intended Play Store architecture because Android execution restrictions, packaging complexity, portability, and release concerns made it a poor foundation.

## 2.4 Backend before cosmetics

The project repeatedly established that backend reliability comes before major visual redesign.

A beautiful dashboard over unreliable server execution is not progress.

## 2.5 Fail closed

MineHost should prefer refusing an operation over silently corrupting a world or falsely claiming success.

Examples:

- world compatibility failure -> reject/import safely
- runtime failure -> mark runtime unavailable
- server readiness failure -> do not report `ONLINE`
- corrupted download -> do not launch
- unsupported world feature -> preserve original world and report incompatibility

## 2.6 Protect proven components

The following components are considered protected unless a definite bug is demonstrated:

- native JVM launcher
- Java runtime extraction/normalisation
- Java runtime validation
- CMake/native configuration
- packaged native launcher binary
- known-working engine launch commands
- existing engine download system

---

# 3. High-Level Architecture

The intended architecture is roughly:

```text
                    ┌──────────────────────────────┐
                    │          MineHost UI          │
                    │ Android screens / controls    │
                    └──────────────┬───────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │     Server Management Core    │
                    │ lifecycle / state / commands │
                    └──────────────┬───────────────┘
                                   │
                ┌──────────────────┼──────────────────┐
                │                  │                  │
                ▼                  ▼                  ▼
       ┌────────────────┐ ┌────────────────┐ ┌──────────────────┐
       │ Java Path      │ │ Bedrock Path   │ │ Future Services  │
       │ Paper/etc.     │ │ Nukkit family  │ │ FRP / Playit etc.│
       └───────┬────────┘ └───────┬────────┘ └──────────────────┘
               │                  │
               ▼                  ▼
       ┌────────────────┐ ┌───────────────────────────────┐
       │ Native JVM     │ │ Bedrock World Compatibility   │
       │ Launcher       │ │ Core + Engine Adapters        │
       └───────┬────────┘ └───────────────┬───────────────┘
               │                          │
               ▼                          ▼
       ┌────────────────┐       ┌──────────────────────────┐
       │ Java Runtime   │       │ Nukkit-MOT / Cloudburst  │
       │ 17/21/25 ARM64 │       │ PNX / PM1E               │
       └───────┬────────┘       └────────────┬─────────────┘
               │                             │
               └──────────────┬──────────────┘
                              ▼
                   ┌───────────────────────────┐
                   │   Real Server Process     │
                   │   real sockets / I/O       │
                   │   persistent world data    │
                   └────────────┬──────────────┘
                                │
                                ▼
                    ┌───────────────────────────┐
                    │ LAN / Android networking │
                    │ future optional tunnel    │
                    └───────────────────────────┘
```

---

# 4. Runtime Architecture

## 4.1 Android application layer

The Android application is responsible for:

- server creation
- server selection
- start/stop/restart
- engine/version selection
- runtime selection
- console display
- player information
- command sending
- file/world management
- server configuration
- lifecycle management
- download/setup orchestration
- error reporting

The UI is not supposed to own the server process directly.

A core server-management layer should own process state, lifecycle, and runtime decisions.

---

# 5. Server Process Model

Each server should have a persistent server directory/profile.

Conceptually:

```text
MineHost/
├── servers/
│   ├── <server-id>/
│   │   ├── engine/
│   │   ├── runtime/
│   │   ├── world/
│   │   ├── logs/
│   │   ├── config/
│   │   └── metadata/
│   └── ...
├── runtimes/
├── downloads/
├── cache/
└── backups/
```

The exact physical paths can evolve, but the conceptual separation is important.

A server should not depend on temporary extraction state once setup is complete.

---

# 6. Process Lifecycle

The target process state machine is conceptually:

```text
CREATED
   │
   ▼
PREPARING
   │
   ├── runtime ready
   ├── engine ready
   ├── files ready
   └── configuration ready
   │
   ▼
STARTING
   │
   ▼
WAITING_FOR_READY
   │
   ├── success ─────────► ONLINE
   │
   └── timeout/crash ──► ERROR
                             │
                             ▼
                           STOPPED
```

A process can also transition:

```text
ONLINE → STOPPING → STOPPED
ONLINE → CRASHED
ONLINE → RESTARTING
```

The application must not equate "process exists" with "server is online".

---

# 7. Real Console I/O

MineHost is intended to use real process I/O.

### Output path

```text
Server process stdout/stderr
          ↓
Process reader
          ↓
Server engine parser/state layer
          ↓
Log buffer
          ↓
UI live console
```

### Command path

```text
UI command input
      ↓
Server manager
      ↓
process stdin / engine command channel
      ↓
real server
```

No fake command-success response should be generated by MineHost.

---

# 8. Android Foreground Service

A long-running server cannot be treated like a normal short-lived Activity task.

The intended design therefore uses an Android foreground service for server execution.

The service is responsible for:

- keeping the process lifecycle alive
- tracking active server state
- continuing operation when the UI is not visible
- exposing notifications/status
- handling stop/restart
- communicating server state back to the application

The Android lifecycle must be tested against:

- app backgrounding
- activity recreation
- process pressure
- service restart conditions
- explicit user stop
- device reboot behavior where supported/planned

---

# 9. Native JVM Architecture

The Java server path relies on a packaged/native launcher rather than assuming the device has a usable system Java.

The project established a native JVM launcher approach because:

- Android phones cannot be assumed to have a standard user-accessible Java installation
- Minecraft Java server software needs a controlled runtime
- Java versions differ by server/software requirements

The launcher selects a validated bundled runtime and launches the server with the correct working directory/environment.

### Protected components

Do not rewrite without proof:

- native launcher
- CMake integration
- packaged native launcher binary
- runtime extraction
- runtime validation
- engine Java launch commands

---

# 10. Java Runtime Strategy

The project has targeted ARM64 Java runtimes including:

- Java 17
- Java 21
- Java 25

Runtime selection should be based on engine/server requirements rather than user guesswork.

Historically:

- Java 17 was verified for PowerNukkit.
- A Java 21 path was tested and encountered archive/installation issues during earlier work.
- Later runtime setup was improved and Java 17 installation/validation was logged as successful.

The runtime system must perform:

1. download
2. extraction
3. normalization
4. executable/setup handling
5. validation
6. caching
7. reuse
8. engine compatibility selection

Runtime corruption should result in a clear failure, not a mysterious server crash.

---

# 11. Historical PRoot/Ubuntu/Box64 Approach

This was an important earlier architecture and is **not the current direction**.

The earlier concept was:

```text
Android
 ↓
Socket Bridge
 ↓
PRoot
 ↓
Ubuntu ARM64 rootfs
 ↓
Box64
 ↓
Minecraft Bedrock x86_64 server
```

It attempted to solve x86_64 Bedrock execution on ARM64 Android.

Important discoveries during this phase included:

- Android 10+ execution/W^X restrictions
- `filesDir` noexec-related behavior
- PRoot environment complexity
- architecture mismatch issues
- `/proc` and `/dev` mount requirements
- Box64 installation/integration problems
- rootfs extraction complexity
- binary execution restrictions
- Play Store architecture concerns

The project subsequently moved away from this approach.

**Do not resurrect PRoot/Termux as the default solution unless the architecture is explicitly reconsidered.**

---

# 12. Bedrock Architecture

Bedrock is handled primarily through Bedrock-compatible Java server engines.

The targeted modern engines became:

1. Nukkit-MOT
2. Cloudburst Nukkit
3. PowerNukkitX
4. PM1E

Old PowerNukkit was eventually removed from the modern compatibility target.

The purpose is not to pretend that these are Mojang Bedrock Dedicated Server binaries.

They are real server engines capable of accepting Bedrock protocol traffic.

---

# 13. Verified Bedrock Runtime Milestones

A significant verified milestone was Nukkit-MOT.

The project recorded a baseline where Nukkit-MOT:

- downloaded automatically
- used packaged Java 17
- started successfully
- opened UDP port `19132`
- responded through RakNet
- advertised a Bedrock version/protocol
- could accept an actual Bedrock client connection

A later baseline described Nukkit-MOT Build 1361 and compatibility with Bedrock `1.26.30`, protocol `1001`.

This proved that local real Bedrock-compatible server hosting on the Android architecture was technically viable.

---

# 14. PowerNukkitX Milestone

PowerNukkitX also reached a real-start state.

The logs showed:

- Java runtime ready
- PowerNukkitX starting
- worlds loading
- server opening `0.0.0.0:19132`

This established that the engine abstraction could launch multiple Bedrock engines rather than being hardwired to a single server implementation.

---

# 15. Universal Bedrock World Compatibility

This became the main Bedrock engineering problem.

A Bedrock client joining a server is not enough.

Modern Bedrock worlds contain version-dependent:

- block state IDs
- palettes
- runtime IDs
- block entities
- entities
- player data
- dimension information
- modern world metadata

Therefore MineHost introduced a conceptual architecture:

```text
Modern Bedrock World
        ↓
MineHost World Compatibility Core
        ↓
Engine Adapter
        ↓
Nukkit-MOT / Cloudburst / PNX / PM1E
```

The purpose is to preserve the user's Bedrock world instead of allowing the engine to silently reinterpret unsupported data.

---

# 16. World Compatibility Goals

The adapter system is intended to preserve, where supported:

- terrain
- chunks
- structures/buildings
- chests
- inventories
- block states
- block entities
- entities/mobs
- Nether
- End
- world spawn
- player position
- player dimension
- player inventory
- armor
- off-hand
- Ender Chest
- gamemode
- relevant world metadata
- supported packs/data

The compatibility system should track what is lossless, transformed, unsupported, or preserved only in the source.

---

# 17. Immutable World Import

World import is intended to be safety-critical.

The original source world should be treated as immutable.

Ideal flow:

```text
Original World
     ↓
Fingerprint / validate
     ↓
Create backup/snapshot
     ↓
Read-only inspection
     ↓
Compatibility decision
     ↓
Adapter conversion
     ↓
Validate converted world
     ↓
Activate only after success
```

If conversion fails:

```text
Converted copy rejected
        ↓
Original remains untouched
```

This avoids destructive migration.

---

# 18. World Compatibility Stages

The planned compatibility development was organized into a staged program, roughly:

### Stage 1 — Baseline audit
Understand current formats and engine assumptions.

### Stage 2 — Read-only inspector
Parse and report world contents without modifying them.

### Stage 3 — Immutable import safety
Never mutate the original source world.

### Stage 4 — Safety gate
Block imports that cannot be safely handled.

### Stage 5 — Core interfaces
Create engine-neutral compatibility interfaces.

### Stage 6 — Nukkit-MOT prototype
Build the first working adapter.

### Stage 7 — Block-state adapter
Handle modern runtime/state mappings.

### Stage 8 — Block entities
Handle modern special blocks and metadata.

### Stage 9 — Player data migration
Preserve position, inventory, dimensions, etc.

### Stage 10 — Acceptance tests
Test real worlds, restarts, dimensions, and player state.

### Stage 11 — Generalise adapter core
Remove engine-specific duplication.

### Stage 12 — Cloudburst
Add adapter.

### Stage 13 — PM1E
Add adapter.

### Stage 14 — PowerNukkitX
Add adapter.

### Stage 15 — Universal engine selection
Select a safe engine based on compatibility.

### Stage 16 — Reliability
Add regression tests, recovery behavior, and final validation.

---

# 19. Known Bedrock Compatibility Problems

The world compatibility work exposed concrete problems.

Missing runtime IDs included:

- `11893`
- `11910`
- `11911`
- `11912`
- `11913`
- `11926`
- `11927`
- `11928`
- `11929`
- `15221`

Known missing/unsupported block entities included:

- `SporeBlossom`
- `Vault`
- `TrialSpawner`

Player state problems included:

- player appearing at world spawn instead of saved location

These are not cosmetic bugs. They are evidence that raw world compatibility was not yet complete.

---

# 20. Bedrock Acceptance Standard

Bedrock should not be declared "done" because the server starts.

A meaningful acceptance test must include:

- clean install
- server setup
- engine startup
- UDP bind
- RakNet response
- actual client join
- existing real-world import
- terrain integrity
- structures
- containers
- block entities
- entities
- player location
- player inventory
- Nether
- End
- stop
- restart
- persistence
- failure behavior
- source-world safety

---

# 21. Java Edition Architecture

Java Edition is a separate path from Bedrock.

The Java design is based around actual Java server software, with **Paper** as an important targeted implementation.

The important distinction is:

```text
Bedrock
 → Bedrock protocol engine family
 → Bedrock world compatibility

Java
 → Paper / Java server software
 → JVM runtime
 → Java world format
```

The two paths should share common MineHost process/lifecycle infrastructure but should not pretend their world formats or server semantics are interchangeable.

---

# 22. Paper Integration

The intended Paper setup flow was designed around:

```text
User chooses Minecraft version
          ↓
MineHost resolves exact compatible Paper build
          ↓
Select stable release
          ↓
Download
          ↓
Validate artifact
          ↓
Select required Java runtime
          ↓
Launch with native JVM
          ↓
Wait for real readiness
          ↓
Report ONLINE
```

The server artifact should be cached after successful verification.

Normal restart should not need to redownload the Paper server.

---

# 23. Paper Rules

The project established strict rules for Paper:

- use official Paper release information
- prefer stable builds
- resolve the exact requested Minecraft version
- do not silently substitute an unrelated version
- validate downloaded files
- use a proper User-Agent
- cache verified server artifacts
- avoid unnecessary network dependency during restart
- select the correct Java version
- keep EULA consent explicit
- never automatically claim that EULA acceptance happened
- use real server readiness
- use persistent server data

The design explicitly rejected:

- fake `ONLINE`
- hardcoded Paper build IDs
- unsafe dynamic version fallback
- automatically accepting EULA
- silently using the device's system Java
- requiring internet for every ordinary restart

---

# 24. Paper Stabilization Problems Identified

Earlier Paper integration reviews identified possible or observed issues such as:

- incorrect Paper release parsing
- release/channel filtering problems
- unsupported versions being accepted
- exact Minecraft version propagation issues
- incorrect Java-runtime mapping
- duplicate live version resolution
- unnecessary network dependence
- cache-scoping issues
- hostname validation concerns
- missing required HTTP User-Agent
- static checksum assumptions for dynamic releases
- EULA authority/ownership cleanup
- separation of Java-specific properties
- CI tests needing to execute before APK assembly

These were targeted for stabilization, but the final physical-device acceptance of all fixes was not established as complete in the last verified history.

---

# 25. Server Version Management

A server version-management system was part of the roadmap.

Desired behavior:

- show supported Minecraft versions
- show engine versions
- distinguish stable/experimental
- download exact selected version
- retain installed versions
- allow switching safely
- prevent incompatible runtime selection
- preserve worlds
- support rollback

Version selection must not create accidental version changes.

---

# 26. Engine Download System

MineHost has an automatic engine download/setup concept.

Desired flow:

```text
User selects engine/version
        ↓
Resolve official/source artifact
        ↓
Download
        ↓
Validate
        ↓
Install into server profile
        ↓
Run compatibility/setup checks
        ↓
Launch
```

Downloaded components should be cached and reused when verified.

---

# 27. File and World Management

The intended application includes a file manager/world manager.

Planned responsibilities include:

- browse server files
- import worlds from device storage
- export worlds
- backup worlds
- restore backups
- manage configuration files
- inspect logs
- protect important directories

World operations should integrate with the compatibility safety layer rather than bypass it.

---

# 28. Planned UI Areas

The UI roadmap included screens/sections such as:

## Home Dashboard

- server cards
- online/offline status
- start/stop controls
- server resource information

## Server Management

- engine
- version
- port
- runtime
- server settings

## Live Console

- real-time logs
- command input
- clear/search controls

## Players

- real online-player list
- player details where supported
- kick/command controls where safely supported

## File Manager

- browse
- edit
- import/export

## Server Version Manager

- installed versions
- supported versions
- update/switch

## Plugin Manager

- plugin installation
- enable/disable
- version compatibility

## Marketplace

- future distribution/discovery layer

## Tunnel / Network

- future public-access system

## World Map

- future live map

---

# 29. Plugin / Add-On Marketplace

A plugin/add-on marketplace was part of the future roadmap.

Potential responsibilities:

- discover supported plugins/add-ons
- verify compatibility
- download
- install
- enable/disable
- version management
- dependency handling

This must not be allowed to destabilize the core server system.

The marketplace is a later layer, not a reason to delay server reliability.

---

# 30. Tunneling / Public Access

The project considered public-access tunneling.

The preferred direction discussed was:

- **FRP (Fast Reverse Proxy)**

Other historical concepts included Playit-style networking.

However, tunneling was intentionally deferred behind:

1. real local server execution
2. stable process lifecycle
3. stable world compatibility
4. reliable local networking

The server must work perfectly on LAN before public tunneling becomes a core release concern.

---

# 31. Server.properties / Configuration UI

Future MineHost versions should expose safe server configuration through UI.

Potential examples:

- server name
- port
- game mode
- difficulty
- max players
- online-mode where applicable
- MOTD
- whitelist
- view distance
- simulation distance
- memory/runtime options

The implementation should generate/edit engine-specific configuration safely rather than assuming all engines share one schema.

---

# 32. Multiple Servers

A planned major feature is multiple simultaneous servers.

The architecture should therefore support:

```text
MineHost
 ├── Server A
 │    ├── runtime
 │    ├── engine
 │    ├── world
 │    └── process
 │
 ├── Server B
 │    ├── runtime
 │    ├── engine
 │    ├── world
 │    └── process
 │
 └── Server C
      ├── runtime
      ├── engine
      ├── world
      └── process
```

Each server needs isolated state and a unique port.

---

# 33. Backups and Repair

A planned backup/repair system should eventually support:

- scheduled backups
- manual backups
- pre-migration backups
- pre-engine-switch snapshots
- corruption recovery
- failed-import rollback
- backup verification

World compatibility makes this especially important.

---

# 34. Google Login / Supabase

A future roadmap item is account/authentication using Google login backed by Supabase.

This is a later service layer.

It should not become a hidden dependency for local server hosting.

The local server should continue to work without an account where product policy allows.

---

# 35. Live Web World Map

A future feature is a live web world map.

Potential concept:

```text
Server world
   ↓
Map renderer
   ↓
Local web server / embedded viewer
   ↓
Browser UI
```

This is a future visualization feature, not a prerequisite for core hosting.

---

# 36. Experimental GPU Acceleration

GPU acceleration was considered as an experimental future performance feature.

It is not part of the core architecture.

The app must remain functional without it.

---

# 37. Play Store Publication Strategy

The product must be designed as a legitimate Android application rather than a wrapper around another Android terminal environment.

Important release principles:

## Standalone

No Termux/PRoot dependency.

## Real functionality

Real server execution and networking.

## Permissions minimisation

Only request permissions genuinely required by the app.

## Foreground service compliance

Use Android-supported service behavior for long-running server execution.

## Transparent EULA handling

For software that requires EULA acceptance, user consent must be explicit.

## Download transparency

Remote engine/runtime downloads should be understandable and validated.

## Data Safety

The eventual Play Store Data Safety declaration must match actual app behavior.

## Store listing truthfulness

Do not claim a capability that has not passed the acceptance tests.

---

# 38. Current Feature Status

This table is a project-level classification based on the latest verified historical state.

| Feature | Status | Notes |
|---|---|---|
| Native Android app foundation | ✅ Implemented | Established project base |
| Local server hosting architecture | ✅ Implemented | Core direction proven |
| Real process execution | ✅ Implemented | Real server process model |
| Live console I/O | ✅ Implemented | Required real process I/O |
| Foreground service concept | ✅ Implemented/Integrated | Must still receive release acceptance testing |
| Native JVM launcher | ✅ Protected/Implemented | Do not rewrite without definite bug |
| Java 17 runtime | ✅ Verified | Used successfully by Bedrock engines |
| Java 21 runtime | 🟡 Implemented/Hardening | Historical archive/runtime issues occurred |
| Java 25 runtime | 🟡 Implemented/Hardening | Validation still part of acceptance |
| Nukkit-MOT | ✅ Verified baseline | Real server launch/network test achieved |
| PowerNukkitX | ✅ Verified launch baseline | Real engine start achieved |
| PM1E | 🟡 Targeted | Needs final acceptance coverage |
| Cloudburst | 🟡 Targeted/Previously deferred in a baseline | Requires current verification |
| Old PowerNukkit | ❌ Not a modern target | Superseded by newer engine targets |
| Automatic engine download | ✅ Implemented baseline | Must remain validated |
| Server version management | 🟡 Planned/in progress | Not final release-complete |
| Bedrock world inspector | 🟡 Planned/in progress | Part of adapter program |
| Universal world compatibility core | 🟡 In progress | Main Bedrock blocker |
| Runtime-ID mappings | 🟡 In progress | Known missing IDs |
| Block-entity compatibility | 🟡 In progress | Known missing entities |
| Player-data migration | 🟡 In progress | Saved position was a known issue |
| Immutable world import | 🟡 Designed / partially implemented | Needs full acceptance proof |
| Paper Java Edition | 🟡 In progress/stabilization | Real integration exists, final acceptance not proven |
| Exact Paper version resolver | 🟡 In progress | Stability issues identified historically |
| Paper artifact validation | 🟡 In progress | Must be verified in final flow |
| Paper EULA handling | 🟡 In progress | Explicit user acceptance required |
| Java server persistent worlds | 🟡 Targeted | Acceptance testing required |
| Multiple servers | 🟡 Planned | Architecture should support it |
| File manager | 🟡 Planned/in progress | Roadmap |
| Plugin manager | 🟡 Planned | Roadmap |
| Marketplace | 🟡 Planned | Later phase |
| FRP tunnel | 🟡 Planned | Deferred until local hosting is stable |
| Live web map | 🟡 Planned | Future |
| Google login / Supabase | 🟡 Planned | Future |
| GPU acceleration | 🟡 Experimental future | Non-core |
| Play Store beta | ❌ Not yet verified | Requires release gates |

---

# 39. Biggest Current Blocker

The largest technical blocker is not Android UI.

It is **reliable modern Bedrock world compatibility**.

The basic engine launch problem was substantially solved.

The hard problem became preserving arbitrary modern Bedrock world data when moving it into an Android-compatible server engine.

This is why MineHost must not stop at:

```text
Server started
```

It must reach:

```text
Real world imported
        ↓
Real structures preserved
        ↓
Real player state preserved
        ↓
Modern blocks/entities preserved
        ↓
Real client joins
        ↓
Gameplay works
        ↓
Server restarts
        ↓
Data is still correct
```

---

# 40. Second Major Blocker

The second major blocker is **release acceptance**.

A feature can be implemented but still not be release-ready.

The final release needs:

- clean-device install
- first-run setup
- runtime download
- engine download
- server creation
- server launch
- actual network readiness
- client connection
- world persistence
- stop/start
- app background behavior
- service behavior
- storage/error behavior
- offline restart/cached artifacts where supported
- crash/failure reporting
- release build verification

---

# 41. Current Regression/Risk Areas

## Runtime regressions

Changes to native launcher/runtime code can break all engines.

## Versioning regressions

Adding version management previously caused server behavior to regress.

This is why version resolution needs strict tests.

## World corruption risk

Incorrect conversion is much more serious than a server crash.

## False readiness

Reporting `ONLINE` too early is unacceptable.

## Network dependence

A server should not need to re-resolve its already-installed artifact every time it starts.

## Android lifecycle

Background/service behavior needs real-device testing.

## Storage

Large runtimes, engines, worlds, logs, and backups can consume substantial device storage.

---

# 42. Testing Strategy

MineHost should use layered testing.

## Layer 1 — Unit tests

Test:

- version parsing
- runtime selection
- metadata handling
- artifact validation
- world compatibility rules
- state transitions

## Layer 2 — Integration tests

Test:

- download
- extraction
- launch
- process lifecycle
- command I/O

## Layer 3 — Device tests

Test:

- ARM64 Android
- foreground service
- background behavior
- storage
- networking
- real client connection

## Layer 4 — World acceptance

Use real Bedrock worlds.

## Layer 5 — Release candidate

Test the exact release AAB/APK behavior.

---

# 43. CI/CD

GitHub Actions was part of the project.

The intended pipeline should eventually include:

```text
Commit
  ↓
Static checks
  ↓
Unit tests
  ↓
Integration tests
  ↓
Build
  ↓
Package verification
  ↓
APK/AAB assembly
```

Important historical lesson:

**CI should fail before an APK is produced when essential tests fail.**

Do not let the pipeline produce a green-looking artifact from a broken backend.

---

# 44. Gradle Notes

The project encountered Gradle/wrapper problems historically.

A specific rule became important:

> Do not “fix” or replace a corrupted Gradle wrapper blindly if it belongs to the protected baseline. Work around it only when the actual failure is understood.

Build-system changes can have broad effects.

---

# 45. Package / Target SDK

The project targeted modern Android publishing constraints, with Target SDK 35 discussed.

The final release must verify:

- target SDK compliance
- service declarations
- notification behavior
- storage access
- networking
- background limits
- supported Android versions

---

# 46. Security Principles

MineHost is a server-hosting app, so the security boundary matters.

Important principles:

- validate downloaded artifacts
- restrict file operations to intended server roots
- sanitize filenames/paths
- do not allow arbitrary path traversal
- do not trust server configuration as executable code
- validate network inputs
- isolate separate server instances
- avoid unsafe shell command concatenation
- handle crash states without executing stale commands
- protect backups and imported worlds

---

# 47. What NOT to Do

The following patterns conflict with the project's direction:

### Do not

- reintroduce PRoot just because a problem is hard
- reintroduce Termux as a dependency
- fake readiness
- fake player lists
- bundle random server binaries without validation
- overwrite user worlds during conversion
- rebuild the native launcher unnecessarily
- change Java runtime extraction without evidence
- mix Java and Bedrock world logic
- add marketplace functionality before server stability
- build fancy UI to hide backend failures
- claim Play Store readiness without physical-device acceptance
- blindly replace working components because a new architecture looks cleaner

---

# 48. Future Product Vision

The long-term MineHost product is intended to become a complete Android Minecraft server manager.

Possible end-state feature set:

```text
MineHost
│
├── Dashboard
│
├── Java Servers
│   ├── Paper
│   ├── other supported engines
│   └── version manager
│
├── Bedrock Servers
│   ├── Nukkit-MOT
│   ├── Cloudburst
│   ├── PNX
│   └── PM1E
│
├── World Compatibility
│   ├── Inspector
│   ├── Adapter
│   ├── Backups
│   └── Repair
│
├── Console
├── Players
├── Files
├── Plugins
├── Marketplace
├── Networking / FRP
├── World Map
└── Account / Cloud services
```

The key requirement is that optional features remain optional and do not destroy the local-first architecture.

---

# 49. Prioritized Roadmap

## Phase A — Stabilize existing core

- Freeze native JVM launcher.
- Freeze runtime extraction/validation.
- Freeze working engine launch commands.
- Fix only proven defects.
- Establish reproducible builds.

## Phase B — Finish Bedrock compatibility

- complete runtime-ID mapping
- handle block entities
- migrate player data
- test Nether/End
- validate containers
- implement immutable import
- implement backup/rollback
- establish acceptance corpus

## Phase C — Finish Java path

- complete Paper resolver
- stable channel/version rules
- artifact validation
- correct Java mapping
- explicit EULA
- real readiness detection
- cached restart
- persistent-world tests

## Phase D — Release hardening

- clean installation
- foreground service
- error handling
- permissions
- storage behavior
- Android lifecycle
- release build
- AAB verification
- Play Store metadata
- Data Safety documentation

## Phase E — Beta

Start with a controlled internal/closed testing group.

Collect:

- crashes
- runtime failures
- world compatibility failures
- device-specific issues
- memory/storage issues
- network failures

## Phase F — Post-beta expansion

Only after core hosting is stable:

- multiple servers
- plugins
- marketplace
- FRP
- web map
- account sync
- additional engines
- performance features

---

# 50. Definition of Done — MineHost Beta

MineHost should not be treated as beta-ready until the following are true.

## Core

- [ ] App installs cleanly on supported Android devices.
- [ ] App starts without requiring Termux or root.
- [ ] Runtime setup works from a clean install.
- [ ] Server creation works.
- [ ] Server start/stop/restart works.
- [ ] Real console output is visible.
- [ ] Real commands reach the server.
- [ ] Server status reflects actual state.

## Bedrock

- [ ] Nukkit-MOT baseline passes.
- [ ] Real Bedrock client can join.
- [ ] Real world import works.
- [ ] Modern mappings pass.
- [ ] Block entities pass.
- [ ] Player state passes.
- [ ] Nether passes.
- [ ] End passes.
- [ ] Restart persistence passes.
- [ ] Failed conversion cannot destroy the original world.

## Java

- [ ] Paper can be installed from a clean device.
- [ ] Correct Java runtime is selected.
- [ ] EULA is explicitly accepted.
- [ ] Artifact is validated.
- [ ] Real Java client can join.
- [ ] World persists across restart.
- [ ] Cached restart works where expected.
- [ ] Real readiness detection passes.

## Android

- [ ] Foreground service is compliant.
- [ ] Background behavior is stable.
- [ ] Stop/restart behavior is reliable.
- [ ] Storage failures are handled.
- [ ] Permissions are justified.
- [ ] Target SDK is correct.

## Store

- [ ] Release AAB builds reproducibly.
- [ ] Internal/closed Play testing succeeds.
- [ ] Store claims match verified features.
- [ ] Data Safety information is accurate.
- [ ] Privacy/legal material is prepared.
- [ ] Crash and ANR behavior is acceptable.

---

# 51. Current Project Position

The project is **not starting from zero**.

The difficult foundational work that has already been established includes:

- native Android application architecture
- real local server process execution
- Java runtime architecture
- native JVM launcher
- automatic engine/runtime setup
- real Bedrock-compatible engine execution
- network socket/RakNet verification
- PowerNukkitX launch path
- multi-engine direction
- Bedrock world compatibility architecture
- safety-first world import principles
- Paper Java Edition architecture
- explicit Play Store-oriented engineering constraints

The project is currently closer to:

```text
Foundational architecture       ████████████████████  largely established
Real local server execution     ████████████████████  established
Bedrock engine execution        ████████████████░░░░  established baseline
Bedrock world compatibility     ████████░░░░░░░░░░░░  main active work
Java/Paper release hardening    ████████████░░░░░░░░  incomplete verification
Play Store release hardening    ████████░░░░░░░░░░░░  not yet final
```

These bars are qualitative project-state indicators, not measured percentages.

---

# 52. Immediate Next Action

The highest-value action when development resumes is:

> **Use the latest MineHost source archive/build as the source of truth, audit exactly what is currently implemented, and continue from the last verified Bedrock compatibility + Paper stabilization checkpoint rather than rebuilding the launcher/runtime architecture.**

The audit should explicitly answer:

1. What from the planned compatibility stages is actually present?
2. Which runtime-ID mappings are currently handled?
3. Which block entities are handled?
4. How is player data currently migrated?
5. What is the exact current Paper resolver behavior?
6. Which native/runtime components remain unchanged from the verified baseline?
7. Which failures reproduce on a physical ARM64 device?
8. What must be fixed before an internal Play Store build?

---

# 53. Long-Term Product Goal

The final vision is:

> **MineHost becomes a genuine Android Minecraft hosting platform where a user can create, configure, start, stop, manage, back up, and expose real Minecraft Java and Bedrock-compatible servers directly from a phone, with safe world handling and no dependency on a desktop, Termux, PRoot, or root access.**

The most important product promise is not the UI.

It is this:

**When MineHost says the server is running, the server is actually running.  
When MineHost says the world is compatible, the world is actually safe to use.  
When MineHost says a feature works, it has been tested against the real server/runtime rather than simulated by the app.**

That standard is what separates MineHost from the many Android "Minecraft server" apps that are ultimately just UI wrappers.

---

# 54. Historical Lessons

The project repeatedly demonstrated several important engineering lessons:

1. **Architecture shortcuts create larger problems later.**
   The PRoot/Ubuntu/Box64 path consumed substantial effort but was ultimately rejected.

2. **The hardest problem is often data compatibility, not process launching.**
   Once real engines ran, Bedrock world preservation became the dominant challenge.

3. **A working baseline is more valuable than a cleaner rewrite.**
   The native runtime and launcher layers must be protected.

4. **Versioning can regress a previously working system.**
   Every resolver/version-management change needs regression tests.

5. **Play Store readiness is an acceptance problem, not a UI problem.**
   A polished app is not ready if its backend fails on a clean device.

6. **Fail-closed is essential for user worlds.**
   It is better to reject an unsupported world than silently corrupt it.

7. **Backend stabilization must precede feature expansion.**
   Marketplace, tunneling, maps, login, and cosmetic redesign are downstream of core reliability.

---

# 55. Canonical Decision Summary

## Keep

- native Android
- local-first execution
- real processes
- live I/O
- foreground service
- packaged Java runtimes
- native JVM launcher
- engine adapter architecture
- Nukkit-MOT-first world compatibility
- explicit EULA flow
- artifact validation
- persistent worlds
- backups/rollback
- FRP as a later networking layer

## Avoid

- PRoot
- Termux dependency
- root requirement
- fake state
- fake console
- unsafe world mutation
- arbitrary engine substitutions
- unnecessary native runtime rewrites
- premature marketplace/tunneling work

---

# 56. Final Status Snapshot

**MineHost is an advanced prototype / pre-beta system, not yet a verified Play Store beta release.**

The core local-server architecture is real and substantially established.

The highest-value active engineering work remains:

**1. Modern Bedrock world compatibility**  
**2. Java/Paper final stabilization verification**  
**3. Physical-device release acceptance**  
**4. Play Store release hardening**

Everything else should remain secondary until these gates are green.

---

# Appendix A — Important Historical Terms

- **MineHost / HostMine** — project/product
- **Nukkit-MOT** — modern Bedrock-compatible engine target
- **Cloudburst** — Bedrock-compatible engine target
- **PowerNukkitX / PNX** — Bedrock-compatible engine target
- **PM1E** — Bedrock-compatible engine target
- **Paper** — Java Edition server target
- **PRoot** — historical Linux userspace approach, no longer intended
- **Box64** — historical x86_64 translation approach, tied to obsolete architecture
- **Native JVM launcher** — protected Java execution component
- **World Compatibility Core** — engine-neutral Bedrock data compatibility layer
- **Serializer 42** — part of the modern Bedrock serialization/compatibility workstream
- **FRP** — planned tunneling solution
- **RakNet** — Bedrock networking layer used by compatible server engines
- **Target SDK 35** — Android publication target discussed during development

---

# Appendix B — Source-of-Truth Rule

When this document conflicts with actual source code, build output, or a current verified device test:

1. Current verified source/build/test wins.
2. This document must then be updated.
3. Historical discussions remain historical.
4. Never resurrect an abandoned architecture merely because it appears in an old note.

This document should therefore be treated as the **MineHost project context file**, not as permission to preserve outdated implementation details forever.
