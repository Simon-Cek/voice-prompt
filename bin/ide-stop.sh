#!/usr/bin/env bash
# Stop the runIde IDE + its build daemons. Idempotent — safe to run repeatedly.
set -u

# The IDE itself (log_runIde is unique to this project's sandbox) and the gradle client that owns it.
pkill -9 -f "runIde" 2>/dev/null || true

# Build daemons: killing these prevents a wedged Kotlin/Gradle daemon from hanging the next start.
pkill -9 -f "kotlin-compiler-runner\|kotlin-daemon\|KotlinCompileDaemon" 2>/dev/null || true
pkill -9 -f "GradleDaemon" 2>/dev/null || true

sleep 2
left=$(ps -eo args | grep -iE "runIde|GradleDaemon|kotlin-compiler-runner" | grep -v grep | wc -l)
echo "ide-stop: done ($left build procs remain)"
