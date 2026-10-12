#!/usr/bin/env bash
# Quick status: is the IDE up? Is our plugin loaded? Recent log lines.
set -u
cd "$(dirname "$0")/.." || exit 1
IDELOG=.intellijPlatform/sandbox/copilot-voice/IU-2025.3.6.1/log_runIde/idea.log

echo "=== IDE process ==="
if command -v powershell.exe >/dev/null 2>&1; then
  # Windows (Git for Windows bash): ps aux truncates long java command lines, so use CIM.
  out=$(powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | Where-Object { \$_.CommandLine -match 'log_runIde' } | ForEach-Object { Write-Output ('IDE pid ' + \$_.ProcessId) }" 2>/dev/null)
  if [ -n "$out" ]; then echo "$out"; else echo "(no IDE process running)"; fi
else
  ps -eo pid,etime,args 2>/dev/null | grep -iE "log_runIde" | grep -v grep || echo "(no IDE process running)"
fi
echo ""
echo "=== our plugin in log? (copilot-voice) ==="
grep -iE "copilot-voice|CopilotVoice|PushToTalk" "$IDELOG" 2>/dev/null | tail -4 || echo "(none yet)"
echo ""
echo "=== idea.log tail ==="
tail -6 "$IDELOG" 2>/dev/null
