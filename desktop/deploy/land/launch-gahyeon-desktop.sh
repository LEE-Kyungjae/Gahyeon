#!/usr/bin/env bash
set -euo pipefail

readonly CORE_API_URL="${GAHYEON_CORE_API_URL:-http://127.0.0.1:18080/api}"
readonly TOKEN_FILE="${GAHYEON_CLIENT_TOKEN_FILE:-/home/ubuntu/.config/gahyeon/client-token}"
readonly WINDOWS_EXE="${GAHYEON_WINDOWS_EXE:-C:\\GahyeonPOC\\desktop-airi-current\\win-unpacked\\Gahyeon.exe}"
readonly POWERSHELL="/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"

if [[ ! -s "${TOKEN_FILE}" ]]; then
  printf 'missing Desktop client token: %s\n' "${TOKEN_FILE}" >&2
  exit 1
fi
if [[ ! -x "${POWERSHELL}" ]]; then
  printf 'Windows PowerShell is unavailable: %s\n' "${POWERSHELL}" >&2
  exit 1
fi

GAHYEON_CLIENT_TOKEN="$(<"${TOKEN_FILE}")"
GAHYEON_WINDOWS_EXE="${WINDOWS_EXE}"
export GAHYEON_CLIENT_TOKEN GAHYEON_WINDOWS_EXE
export GAHYEON_CORE_API_URL="${CORE_API_URL}"
export WSLENV="${WSLENV:+${WSLENV}:}GAHYEON_CLIENT_TOKEN:GAHYEON_CORE_API_URL:GAHYEON_WINDOWS_EXE"

curl --fail --silent --show-error --max-time 8 \
  -H "Authorization: Bearer ${GAHYEON_CLIENT_TOKEN}" \
  "${CORE_API_URL}/gahyeon/desktop/speech/status" >/dev/null

"${POWERSHELL}" -NoProfile -Command \
  '$existing = @(Get-Process Gahyeon -ErrorAction SilentlyContinue); ' \
  'if ($existing.Count -gt 0) { $existing | Stop-Process -Force }; ' \
  'Start-Sleep -Milliseconds 400; ' \
  'Start-Process -FilePath $env:GAHYEON_WINDOWS_EXE'
