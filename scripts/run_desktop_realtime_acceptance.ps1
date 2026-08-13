param(
    [Parameter(Mandatory = $true)][string]$PackagedRoot,
    [string]$EvidenceRoot = "",
    [string]$RunId = "",
    [int]$DurationSeconds = 600,
    [int]$Width = 1920,
    [int]$Height = 1080,
    [string]$Python = "python",
    [switch]$RequirePassed
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$PackagedRoot = (Resolve-Path -LiteralPath $PackagedRoot).Path
if (-not $EvidenceRoot) {
    $EvidenceRoot = Join-Path $RepoRoot "artifacts\desktop-realtime-acceptance"
}
if (-not $RunId) {
    $RunId = "desktop-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
}
if ($RunId -notmatch '^[a-z0-9][a-z0-9_-]{7,63}$') {
    throw "RunId must be a safe lowercase identifier"
}
if ($DurationSeconds -lt 600 -or $DurationSeconds -gt 3600) {
    throw "DurationSeconds must be between 600 and 3600"
}
if ($Width -lt 1280 -or $Width -gt 7680 -or $Height -lt 720 -or $Height -gt 4320) {
    throw "Desktop acceptance resolution is outside the supported range"
}
$Executables = @(Get-ChildItem -LiteralPath $PackagedRoot -Recurse -File `
    | Where-Object { $_.Name -eq "GahyeonStage.exe" })
if ($Executables.Count -ne 1) {
    throw "Expected exactly one packaged GahyeonStage.exe; found $($Executables.Count)"
}
$Executable = $Executables[0].FullName

New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null
$Raw = Join-Path $EvidenceRoot "raw-desktop.json"
$Acceptance = Join-Path $EvidenceRoot "desktop-acceptance.json"
$RuntimeLog = Join-Path $EvidenceRoot "runtime.log"
foreach ($Path in @($Raw, $Acceptance, $RuntimeLog)) {
    if (Test-Path -LiteralPath $Path) {
        throw "Acceptance evidence already exists and will not be overwritten: $Path"
    }
}

$Arguments = @(
    "-GahyeonRtRunId=$RunId",
    "-GahyeonRtDuration=$DurationSeconds",
    "-GahyeonRtOutput=$Raw",
    "-GahyeonRtExit",
    "-windowed",
    "-ResX=$Width",
    "-ResY=$Height",
    "-log"
)
Write-Host "Running packaged Desktop acceptance: $Executable"
& $Executable @Arguments *>&1 | Tee-Object -FilePath $RuntimeLog
if ($LASTEXITCODE -ne 0) { throw "Packaged GahyeonStage exited with code $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath $Raw -PathType Leaf)) {
    throw "Packaged GahyeonStage exited without atomic raw acceptance evidence"
}

& $Python (Join-Path $RepoRoot "scripts\build_desktop_realtime_acceptance.py") `
    --raw $Raw --output $Acceptance
if ($LASTEXITCODE -ne 0) { throw "Desktop acceptance aggregation failed" }
$VerifyArguments = @($Acceptance)
if ($RequirePassed) { $VerifyArguments += "--require-passed" }
& $Python (Join-Path $RepoRoot "scripts\verify_desktop_realtime_acceptance.py") `
    @VerifyArguments
if ($LASTEXITCODE -ne 0) { throw "Desktop realtime acceptance verification failed" }
Write-Host "Desktop realtime acceptance recorded: $Acceptance"
