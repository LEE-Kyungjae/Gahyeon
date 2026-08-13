param(
    [Parameter(Mandatory = $true)][string]$UnrealRoot,
    [string]$Python = "python",
    [string]$EvidenceRoot = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Project = Join-Path $RepoRoot "unreal\GahyeonStage\GahyeonStageLookingGlass.uproject"
$Plugin = Join-Path $RepoRoot "unreal\GahyeonStage\Plugins\LookingGlass\LookingGlass.uplugin"
$Build = Join-Path $UnrealRoot "Engine\Build\BatchFiles\Build.bat"
$Editor = Join-Path $UnrealRoot "Engine\Binaries\Win64\UnrealEditor-Cmd.exe"
$Version = Join-Path $UnrealRoot "Engine\Build\Build.version"
if (-not $EvidenceRoot) {
    $EvidenceRoot = Join-Path $RepoRoot "artifacts\looking-glass-engine-gate"
}

foreach ($Path in @($Project, $Plugin, $Build, $Editor, $Version)) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Looking Glass Windows gate prerequisite is missing: $Path"
    }
}
$VersionPayload = Get-Content -LiteralPath $Version -Raw | ConvertFrom-Json
if ($VersionPayload.MajorVersion -ne 5 -or $VersionPayload.MinorVersion -ne 6) {
    throw "Gahyeon Looking Glass prototype requires Unreal Engine 5.6"
}

& $Python (Join-Path $RepoRoot "scripts\verify_looking_glass_integration.py")
if ($LASTEXITCODE -ne 0) { throw "Looking Glass lock verification failed" }
& $Python (Join-Path $RepoRoot "scripts\verify_looking_glass_unreal_profile.py")
if ($LASTEXITCODE -ne 0) { throw "Looking Glass project profile verification failed" }

New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null
$BuildLog = Join-Path $EvidenceRoot "build.log"
$AutomationLog = Join-Path $EvidenceRoot "automation.log"

& $Build GahyeonStageEditor Win64 Development "-Project=$Project" -WaitMutex -NoHotReloadFromIDE *>&1 |
    Tee-Object -FilePath $BuildLog
if ($LASTEXITCODE -ne 0) { throw "Looking Glass UE Development Editor build failed" }

& $Editor $Project -unattended -nop4 -nosplash -NullRHI -stdout -FullStdOutLogOutput `
    -GahyeonRequireLookingGlass '-TestExit=Automation Test Queue Empty' `
    '-ExecCmds=Automation RunTests Gahyeon' *>&1 | Tee-Object -FilePath $AutomationLog
if ($LASTEXITCODE -ne 0) { throw "Looking Glass UE Automation process failed" }
$Log = Get-Content -LiteralPath $AutomationLog -Raw
if ($Log -match 'Automation Test Failed|Result=Failed|Result=\{Fail') {
    throw "At least one Looking Glass Gahyeon Automation test failed"
}
if ($Log -notmatch 'Gahyeon\.Runtime\.MockCognitionDelayFailureAndReordering.*(Success|Passed)|Result=\{Success\}.*Gahyeon\.Runtime\.MockCognitionDelayFailureAndReordering') {
    throw "VS-5 Mock Cognition Automation success marker is missing"
}
if ($Log -notmatch 'Gahyeon\.LookingGlass\.PluginAvailableWhenRequired.*(Success|Passed)|Result=\{Success\}.*Gahyeon\.LookingGlass\.PluginAvailableWhenRequired') {
    throw "Looking Glass plugin availability Automation success marker is missing"
}
if ($Log -notmatch 'Gahyeon\.Presentation\.FacialCurveBindingsAreDataDrivenAndBounded.*(Success|Passed)|Result=\{Success\}.*Gahyeon\.Presentation\.FacialCurveBindingsAreDataDrivenAndBounded') {
    throw "VS-8 facial/viseme Automation success marker is missing"
}

$ManifestScript = @'
import datetime, hashlib, json, pathlib, sys
root, project = pathlib.Path(sys.argv[1]).resolve(), pathlib.Path(sys.argv[2]).resolve()
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
value = {
  "schemaVersion": 2, "status": "passed",
  "completedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
  "engineVersion": "5.6", "platform": "Win64", "configuration": "Development",
  "project": str(project), "projectSha256": digest(project),
  "packagedBuild": False,
  "requiredAutomationTests": [
    "Gahyeon.Runtime.MockCognitionDelayFailureAndReordering",
    "Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded"
  ],
  "profileAutomationTest": "Gahyeon.LookingGlass.PluginAvailableWhenRequired",
  "evidence": {
    "buildLog": {"path": "build.log", "sha256": digest(root / "build.log")},
    "automationLog": {"path": "automation.log", "sha256": digest(root / "automation.log")}
  }
}
temporary = root / "manifest.json.tmp"
temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
temporary.replace(root / "manifest.json")
'@
& $Python -c $ManifestScript $EvidenceRoot $Project
if ($LASTEXITCODE -ne 0) { throw "Looking Glass evidence manifest creation failed" }
& $Python (Join-Path $RepoRoot "scripts\verify_unreal_engine_evidence.py") $EvidenceRoot
if ($LASTEXITCODE -ne 0) { throw "Looking Glass evidence verification failed" }
Write-Host "Looking Glass Unreal Windows gate passed: $EvidenceRoot"
