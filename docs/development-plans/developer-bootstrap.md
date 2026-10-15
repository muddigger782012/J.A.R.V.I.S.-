# Developer bootstrap

Status: approved

## Objective
Provide an independent Jarvis Developer APK that reads approved development plans from this GitHub repository and displays Developer build status.

## Scope
- Standalone Android package com.jarvis.developer
- Public GitHub read-only access to approved Markdown development plans
- Read-only GitHub Actions status
- No GitHub write permissions, automatic edits, or production release operations in this milestone

## Acceptance criteria
- Installs alongside standard Jarvis and Beta without package conflict
- Can open this plan from the app
- Displays current Developer workflow run status or a useful error
- Build produces an installable debug APK artifact

## Build target
:developer:assembleDebug

## Security
No bundled credentials. All network calls use HTTPS. No automatic merge or release.

## Rollback
Revert the feature branch commits without touching main.
