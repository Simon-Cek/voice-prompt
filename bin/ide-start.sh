#!/usr/bin/env bash
# Start runIde in a detached `screen` session so it survives the caller shell exiting/timing out.
set -u
cd "$(dirname "$0")/.." || exit 1
ROOT="$PWD"
LOG=/tmp/opencode/runide.out
mkdir -p /tmp/opencode

# Idempotent: clear any prior instance + daemons first (prevents wedged-daemon hangs).
bash bin/ide-stop.sh

# Launch fully detached; gradle output goes to $LOG.
if command -v screen >/dev/null 2>&1; then
  screen -dmS copilot-ide bash -c "cd '$ROOT' && ./gradlew runIde > '$LOG' 2>&1"
  echo "ide-start: launched (screen 'copilot-ide'); log=$LOG"
else
  # Fallback when screen is unavailable (e.g. Git for Windows bash): detach with nohup.
  ( cd "$ROOT" && nohup ./gradlew runIde > "$LOG" 2>&1 & disown )
  echo "ide-start: launched (nohup fallback; screen not found); log=$LOG"
fi
echo "ide-start: give it ~40s to compile + open, then check with: bash bin/ide-status.sh"
