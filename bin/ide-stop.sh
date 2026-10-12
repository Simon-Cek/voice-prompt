#!/usr/bin/env bash
# Stop the runIde IDE + its build daemons. Idempotent — safe to run repeatedly.
set -u

# The IDE itself (log_runIde is unique to this project's sandbox) and the gradle client that owns it.
# Build daemons: killing these prevents a wedged Kotlin/Gradle daemon from hanging the next start.
if command -v pkill >/dev/null 2>&1; then
  pkill -9 -f "runIde" 2>/dev/null || true
  pkill -9 -f "kotlin-compiler-runner\|kotlin-daemon\|KotlinCompileDaemon" 2>/dev/null || true
  pkill -9 -f "GradleDaemon" 2>/dev/null || true
elif command -v powershell.exe >/dev/null 2>&1; then
  # Windows fallback (e.g. Git for Windows bash has no procps): match java/javaw by command line.
  powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | Where-Object { \$_.CommandLine -match 'runIde|GradleDaemon|kotlin-compiler-runner|KotlinCompileDaemon' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" || true
else
  echo "ide-stop: WARNING: no pkill and no powershell.exe; cannot kill by pattern"
fi

sleep 2
left=$(ps aux 2>/dev/null | grep -iE "runIde|GradleDaemon|kotlin-compiler-runner" | grep -v grep | wc -l)
echo "ide-stop: done ($left build procs remain)"
