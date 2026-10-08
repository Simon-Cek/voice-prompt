#!/usr/bin/env bash
# Quick status: is the IDE up? Is our plugin loaded? Recent log lines.
set -u
cd "$(dirname "$0")/.." || exit 1
IDELOG=.intellijPlatform/sandbox/copilot-voice/IU-2025.3.6.1/log_runIde/idea.log

echo "=== IDE process ==="
ps -eo pid,etime,args | grep -iE "log_runIde" | grep -v grep || echo "(no IDE process running)"
echo ""
echo "=== our plugin in log? (copilot-voice) ==="
grep -iE "copilot-voice|CopilotVoice|PushToTalk" "$IDELOG" 2>/dev/null | tail -4 || echo "(none yet)"
echo ""
echo "=== idea.log tail ==="
tail -6 "$IDELOG" 2>/dev/null
