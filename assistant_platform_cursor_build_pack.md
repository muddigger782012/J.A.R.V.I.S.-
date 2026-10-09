# Assistant Platform – Cursor Build Pack

This file is meant to be dropped into a new project folder and read by Cursor Agent.
It contains the product brief, architecture, repository layout, implementation plan,
build instructions, and the exact prompts to use so Cursor can continue development.

---

## 1) Project goal

Build an Android application called **Assistant Platform**.

It is an **offline-first AI assistant / administrator app** with a **secondary coding subsystem**.

Primary goals:
- work offline
- generate small coding projects locally
- manage structured actions and tasks
- support modular privilege backends
- support Shizuku and Dhizuku as optional capability providers
- be expandable into a stronger managed / OEM deployment later

Target result for this phase:
- a working Android MVP
- buildable in Cursor
- produces a debug APK
- simple UI
- modular Kotlin codebase
- local storage and action logging

---

## 2) Product definition

The app is **not** just a chatbot.

It is a structured assistant platform with:
- assistant UI
- command-to-action pipeline
- capability broker
- adapter-based execution
- local storage
- local workspace/project generation
- logging and task history

The coding engine is a service used by the assistant.

---

## 3) Platform targets

- Platform: Android
- Language: Kotlin
- Min SDK: 26
- Target SDK: 34
- Compile SDK: 34
- Package name: `com.assistant.core`

Build output target:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 4) MVP feature scope

The MVP must include:

1. Main assistant screen
2. Command input box
3. Buttons for:
   - Create Project
   - Run Shizuku Test Command
   - Show Capability Status
4. Action output log panel
5. Capability detection at startup
6. SQLite database
7. Project generation into app-local storage
8. Audit logging
9. Standard adapter
10. Shizuku adapter
11. Dhizuku adapter skeleton
12. Policy gate
13. Workflow/task placeholders
14. Clean modular file structure

Not required for MVP:
- real local LLM integration
- full autonomous coding agent
- complete Dhizuku admin implementation
- advanced UI polish
- cloud sync

---

## 5) High-level architecture

Execution pipeline:

User command  
→ intent classification  
→ ActionRequest  
→ PolicyGate  
→ CapabilityBroker  
→ Adapter execution  
→ ActionResult  
→ Audit log  
→ UI update

Main layers:

1. Presentation layer
2. Core engine layer
3. Capability broker layer
4. Services layer
5. Storage layer
6. Optional privilege provider layer

---

## 6) Capability model

Support these capability states:

- standard
- specialAccess
- shizuku
- dhizuku
- deviceOwner
- oemPrivileged
- platformSigned

For MVP:
- implement detection for standard
- implement best-effort detection for Shizuku
- implement placeholder detection for Dhizuku
- implement device owner check
- keep OEM/platform fields as false unless specifically detectable

---

## 7) Repository structure

Use this project layout:

assistant-platform/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/assistant/core/
│       │   ├── MainActivity.kt
│       │   ├── adapters/
│       │   │   ├── StandardAdapter.kt
│       │   │   ├── ShizukuAdapter.kt
│       │   │   ├── DhizukuAdapter.kt
│       │   │   └── SpecialAccessAdapter.kt
│       │   ├── engine/
│       │   │   ├── AssistantEngine.kt
│       │   │   ├── CapabilityBroker.kt
│       │   │   ├── ActionExecutor.kt
│       │   │   ├── ActionRegistry.kt
│       │   │   ├── CapabilityDetector.kt
│       │   │   ├── PolicyGate.kt
│       │   │   └── IntentClassifier.kt
│       │   ├── models/
│       │   │   ├── ActionRequest.kt
│       │   │   ├── ActionResult.kt
│       │   │   ├── CapabilityState.kt
│       │   │   ├── Task.kt
│       │   │   ├── Workflow.kt
│       │   │   └── Project.kt
│       │   ├── services/
│       │   │   ├── FileService.kt
│       │   │   ├── CodingService.kt
│       │   │   ├── SystemService.kt
│       │   │   ├── SchedulerService.kt
│       │   │   └── AuditService.kt
│       │   ├── storage/
│       │   │   ├── Database.kt
│       │   │   ├── ProjectRepository.kt
│       │   │   ├── ActionRepository.kt
│       │   │   └── AuditRepository.kt
│       │   └── workflow/
│       │       └── WorkflowEngine.kt
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml
│           └── values/
│               ├── strings.xml
│               └── themes.xml

---

## 8) Gradle requirements

Use Kotlin Android application plugin.

Dependencies:
- androidx core-ktx
- appcompat
- material
- constraintlayout (optional if useful)
- Shizuku API
- Shizuku provider

Use current stable versions if agent can resolve them, otherwise use versions compatible with AGP 8.x.

Shizuku dependencies:
- `dev.rikka.shizuku:api:13.1.5`
- `dev.rikka.shizuku:provider:13.1.5`

---

## 9) Data model contracts

### ActionRequest
Fields:
- id: String
- actionType: String
- parameters: Map<String, Any?>
- requiredCapability: String
- riskLevel: Int
- allowFallback: Boolean = true

### ActionResult
Fields:
- id: String
- success: Boolean
- adapterUsed: String
- message: String
- output: String? = null

### CapabilityState
Fields:
- standard: Boolean
- specialAccess: Boolean
- shizuku: Boolean
- dhizuku: Boolean
- deviceOwner: Boolean
- oemPrivileged: Boolean
- platformSigned: Boolean

### Project
Fields:
- id: String
- name: String
- rootPath: String
- createdAt: Long

### Task
Fields:
- id: String
- title: String
- status: String
- createdAt: Long

### Workflow
Fields:
- id: String
- name: String
- triggerType: String
- createdAt: Long

---

## 10) Database schema

Use SQLiteOpenHelper.

Create these tables:

### projects
- id TEXT PRIMARY KEY
- name TEXT NOT NULL
- root_path TEXT NOT NULL
- created_at INTEGER NOT NULL

### project_files
- id TEXT PRIMARY KEY
- project_id TEXT NOT NULL
- path TEXT NOT NULL
- hash TEXT
- last_modified INTEGER

### actions
- id TEXT PRIMARY KEY
- action_type TEXT NOT NULL
- parameters TEXT
- required_capability TEXT
- risk_level INTEGER
- created_at INTEGER NOT NULL

### action_queue
- id TEXT PRIMARY KEY
- action_id TEXT NOT NULL
- status TEXT NOT NULL
- scheduled_time INTEGER

### workflows
- id TEXT PRIMARY KEY
- name TEXT NOT NULL
- trigger_type TEXT NOT NULL
- created_at INTEGER NOT NULL

### tasks
- id TEXT PRIMARY KEY
- title TEXT NOT NULL
- status TEXT NOT NULL
- created_at INTEGER NOT NULL

### audit_logs
- id TEXT PRIMARY KEY
- action_id TEXT
- adapter_used TEXT
- status TEXT
- message TEXT
- created_at INTEGER NOT NULL

Cursor should implement repository helpers for insert/read operations used by the MVP.

---

## 11) Core engine responsibilities

### AssistantEngine
- main orchestration entry point
- receives user commands or ActionRequests
- uses IntentClassifier when input is raw text
- asks PolicyGate if action is allowed
- passes execution to CapabilityBroker
- records audit log
- returns ActionResult

### IntentClassifier
For MVP, keep it simple:
- if input contains "create project" → CREATE_PROJECT
- if input contains "run shell" → RUN_SHELL
- if input contains "reboot" → REBOOT_DEVICE
- else → UNKNOWN / unsupported

### PolicyGate
Rules:
- risk 0-1: allow
- risk 2+: require explicit confirmation flag or button path
- REBOOT_DEVICE should be blocked unless Dhizuku/device owner is available

### CapabilityBroker
Selects best adapter:
- STANDARD → StandardAdapter
- SPECIAL_ACCESS → SpecialAccessAdapter
- SHIZUKU → ShizukuAdapter if available
- DHIZUKU → DhizukuAdapter if available
- otherwise fail cleanly

### ActionRegistry
Register supported actions and their defaults.

---

## 12) Adapters

### StandardAdapter
Must support:
- CREATE_PROJECT
- WRITE_FILE
- SHOW_STATUS

### ShizukuAdapter
Must:
- detect availability
- request / check permission if possible
- support RUN_SHELL test command
- return graceful message if Shizuku unavailable

If real shell execution is difficult in first pass, implement:
- availability detection
- permission check
- placeholder result
Then improve in second pass if build remains stable.

### DhizukuAdapter
Implement as skeleton with:
- availability detection placeholder
- REBOOT_DEVICE placeholder
- clear unsupported message when unavailable

### SpecialAccessAdapter
Placeholder adapter for future accessibility/notification/usage access paths.

---

## 13) Services

### FileService
- write text file
- read text file
- ensure directories exist
- list project files

### CodingService
Must implement:
- `createProject(name: String): File`
Behavior:
1. create directory under app files dir:
   `files/projects/{name}/`
2. create `main.py`
3. write this exact content:

```python
def main():
    print("Hello from Assistant Platform")

if __name__ == "__main__":
    main()
```

4. return project root

### SystemService
- produce a basic status summary string
- include app files dir path
- include capability state passed in
- include whether device owner is active

### SchedulerService
Placeholder, but should compile.
Can store tasks without actual scheduling.

### AuditService
- log action results to DB
- load recent audit entries for UI display if feasible

---

## 14) Storage behavior

Project workspace path:
`/data/data/com.assistant.core/files/projects/`

Within the app, use `context.filesDir/projects/`.

When user presses Create Project:
- generate project folder `assistant_demo`
- write `main.py`
- insert project record into DB
- log action result

---

## 15) UI requirements

Main screen should contain:
- EditText for command input
- Button: Create Project
- Button: Run Shizuku Test
- Button: Show Capability Status
- TextView or scrollable area for output log/status

Minimal layout is fine.
Use XML layout, not Jetpack Compose, unless Cursor strongly prefers Compose and can keep it stable.
XML is preferred for speed and compatibility.

Expected behavior:
- Create Project button creates `assistant_demo`
- Run Shizuku Test attempts RUN_SHELL or reports unavailable
- Show Capability Status prints capability info to output log

---

## 16) Capability detection

Create a `CapabilityDetector`.

At startup detect:
- standard = true
- specialAccess = false for MVP unless actual checks are easy
- shizuku = true if Shizuku API says available
- dhizuku = false unless Dhizuku integration is implemented
- deviceOwner = true if DevicePolicyManager reports app is device owner
- oemPrivileged = false
- platformSigned = false

Expose capability state to UI.

---

## 17) Action examples

### CREATE_PROJECT
- requiredCapability: STANDARD
- riskLevel: 1
- parameters: { "name": "assistant_demo" }

### RUN_SHELL
- requiredCapability: SHIZUKU
- riskLevel: 2
- parameters: { "command": "id" }

### SHOW_STATUS
- requiredCapability: STANDARD
- riskLevel: 0
- parameters: {}

### REBOOT_DEVICE
- requiredCapability: DHIZUKU
- riskLevel: 3
- parameters: {}

---

## 18) Build and verification requirements

Cursor must:
1. generate all files
2. fix imports and Gradle issues
3. resolve package names consistently
4. build the debug APK
5. stop only when project compiles successfully

Target output:
`app/build/outputs/apk/debug/app-debug.apk`

If build fails, agent must iterate until resolved.

---

## 19) Cursor workflow instructions

After reading this file, Cursor Agent should:

1. Create the Android project
2. Create all Kotlin packages and files
3. Write the XML layout
4. Add manifest and Gradle config
5. Implement DB and repositories
6. Implement engine, broker, adapters, services
7. Build project
8. Fix all compile errors
9. Produce a buildable APK

---

## 20) Exact prompts to use in Cursor

### Prompt 1: initial generation
Read `assistant_platform_cursor_build_pack.md` and generate the complete Android project exactly as specified.
Create all files, Kotlin classes, Gradle configs, layout XML, and database code.
Use package name `com.assistant.core`.
Then build the project and fix any compile errors.

### Prompt 2: if Cursor stops too early
Continue. Do not summarize.
Actually create the remaining files and wire them together.
Then run the build and fix all errors until the debug APK compiles.

### Prompt 3: after first successful build
Now improve the app:
- make the UI cleaner
- show audit log entries on screen
- show capability status on startup
- ensure Create Project creates `assistant_demo/main.py`
- make Shizuku test button return a clear result

### Prompt 4: optional second phase
Add a local coding-agent placeholder module and a workflow/task center screen, but do not break the existing APK build.

---

## 21) Notes for the coding agent

- Prefer working code over elaborate abstractions.
- Keep modules small and readable.
- Do not leave files empty.
- Placeholder modules must still compile.
- Avoid adding unnecessary libraries.
- Do not block build success on Dhizuku implementation details.
- Keep Dhizuku integration as a safe skeleton if SDK setup is uncertain.
- Prioritize a successful debug build.

---

## 22) What success looks like

The app installs and launches.
Main screen appears.
Pressing Create Project:
- creates local folder
- writes `main.py`
- shows success message in UI
- records audit log

Pressing Show Capability Status:
- prints capability state

Pressing Run Shizuku Test:
- returns available/unavailable and test result

That is the correct MVP handoff point.

