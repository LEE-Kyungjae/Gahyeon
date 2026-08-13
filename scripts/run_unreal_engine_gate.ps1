param(
    [Parameter(Mandatory = $true)][string]$UnrealRoot,
    [string]$Python = "python",
    [string]$EvidenceRoot = "",
    [string]$HeroManifest = "",
    [switch]$CheckOnly,
    [switch]$BuildOnly,
    [switch]$Package
)

$ErrorActionPreference = "Stop"
if ($CheckOnly -and $BuildOnly) { throw "CheckOnly and BuildOnly are mutually exclusive" }
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Project = Join-Path $RepoRoot "unreal\GahyeonStage\GahyeonStage.uproject"
$Build = Join-Path $UnrealRoot "Engine\Build\BatchFiles\Build.bat"
$Editor = Join-Path $UnrealRoot "Engine\Binaries\Win64\UnrealEditor-Cmd.exe"
$Version = Join-Path $UnrealRoot "Engine\Build\Build.version"
$RunUAT = Join-Path $UnrealRoot "Engine\Build\BatchFiles\RunUAT.bat"
if (-not $EvidenceRoot) {
    $EvidenceRoot = Join-Path $RepoRoot "artifacts\unreal-engine-gate-win64"
}

foreach ($Path in @($Project, $Build, $Editor, $Version)) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Unreal Windows gate prerequisite is missing: $Path"
    }
}
if ($Package -and -not (Test-Path -LiteralPath $RunUAT -PathType Leaf)) {
    throw "Unreal packaging prerequisite is missing: $RunUAT"
}
$VersionPayload = Get-Content -LiteralPath $Version -Raw | ConvertFrom-Json
if ($VersionPayload.MajorVersion -ne 5 -or $VersionPayload.MinorVersion -ne 6) {
    throw "GahyeonStage requires Unreal Engine 5.6"
}
if ($HeroManifest) {
    & $Python (Join-Path $RepoRoot "scripts\verify_gahyeon_hero_asset.py") `
        $HeroManifest --require-approved --renderer hero-engine --verify-files
    if ($LASTEXITCODE -ne 0) { throw "approved Hero manifest verification failed" }
    & $Python (Join-Path $RepoRoot "scripts\install_gahyeon_unreal_content.py") `
        $HeroManifest --project (Join-Path $RepoRoot "unreal\GahyeonStage") --check-only
    if ($LASTEXITCODE -ne 0) { throw "Hero content installation check failed" }
}

Write-Host "Unreal gate environment OK: UE 5.6 (Win64) at $UnrealRoot"
if ($CheckOnly) { exit 0 }

New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null
$BuildLog = Join-Path $EvidenceRoot "build.log"
$AutomationLog = Join-Path $EvidenceRoot "automation.log"
& $Build GahyeonStageEditor Win64 Development "-Project=$Project" `
    -WaitMutex -NoHotReloadFromIDE *>&1 | Tee-Object -FilePath $BuildLog
if ($LASTEXITCODE -ne 0) { throw "GahyeonStage UE Development Editor build failed" }
if ($BuildOnly) { exit 0 }

$PackagedBuild = $false
if ($Package) {
    $PackageRoot = Join-Path $EvidenceRoot "package"
    $PackageLog = Join-Path $EvidenceRoot "package.log"
    if (Test-Path -LiteralPath $PackageRoot) {
        throw "package evidence directory already exists: $PackageRoot"
    }
    & $RunUAT BuildCookRun "-project=$Project" -noP4 -platform=Win64 `
        -clientconfig=Development -build -cook -stage -pak -archive `
        "-archivedirectory=$PackageRoot" *>&1 | Tee-Object -FilePath $PackageLog
    if ($LASTEXITCODE -ne 0) { throw "GahyeonStage packaged Development build failed" }
    $InventoryScript = @'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1]).resolve()
files = []
for path in sorted(item for item in root.rglob("*") if item.is_file() and not item.is_symlink()):
    relative = path.relative_to(root).as_posix()
    data = path.read_bytes()
    files.append({"path": relative, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
if not files:
    raise SystemExit("packaged output contains no regular files")
pathlib.Path(sys.argv[2]).write_text(json.dumps({"schemaVersion": 1, "files": files}, indent=2) + "\n", encoding="utf-8")
'@
    & $Python -c $InventoryScript $PackageRoot (Join-Path $EvidenceRoot "package-files.json")
    if ($LASTEXITCODE -ne 0) { throw "packaged build inventory creation failed" }
    $PackagedBuild = $true
}

& $Editor $Project -unattended -nop4 -nosplash -NullRHI -stdout -FullStdOutLogOutput `
    '-TestExit=Automation Test Queue Empty' '-ExecCmds=Automation RunTests Gahyeon' `
    *>&1 | Tee-Object -FilePath $AutomationLog
if ($LASTEXITCODE -ne 0) { throw "GahyeonStage UE Automation process failed" }
$Log = Get-Content -LiteralPath $AutomationLog -Raw
if ($Log -match 'Automation Test Failed|Result=Failed|Result=\{Fail') {
    throw "At least one Gahyeon Automation test failed"
}
if ($Log -notmatch 'Gahyeon\.Runtime\.MockCognitionDelayFailureAndReordering.*(Success|Passed)|Result=\{Success\}.*Gahyeon\.Runtime\.MockCognitionDelayFailureAndReordering') {
    throw "VS-5 Mock Cognition Automation success marker is missing"
}
if ($Log -notmatch 'Gahyeon\.Presentation\.FacialCurveBindingsAreDataDrivenAndBounded.*(Success|Passed)|Result=\{Success\}.*Gahyeon\.Presentation\.FacialCurveBindingsAreDataDrivenAndBounded') {
    throw "VS-8 facial/viseme Automation success marker is missing"
}

$ManifestScript = @'
import datetime, hashlib, json, pathlib, sys
root, project = pathlib.Path(sys.argv[1]).resolve(), pathlib.Path(sys.argv[2]).resolve()
packaged = sys.argv[3].lower() == "true"
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
value = {
  "schemaVersion": 2, "status": "passed",
  "completedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
  "engineVersion": "5.6", "platform": "Win64", "configuration": "Development",
  "project": str(project), "projectSha256": digest(project),
  "packagedBuild": packaged,
  "requiredAutomationTests": [
    "Gahyeon.Runtime.MockCognitionDelayFailureAndReordering",
    "Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded"
  ],
  "evidence": {
    "buildLog": {"path": "build.log", "sha256": digest(root / "build.log")},
    "automationLog": {"path": "automation.log", "sha256": digest(root / "automation.log")}
  }
}
if packaged:
  value["evidence"]["packageLog"] = {"path": "package.log", "sha256": digest(root / "package.log")}
  value["evidence"]["packageInventory"] = {"path": "package-files.json", "sha256": digest(root / "package-files.json")}
temporary = root / "manifest.json.tmp"
temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
temporary.replace(root / "manifest.json")
'@
& $Python -c $ManifestScript $EvidenceRoot $Project $PackagedBuild
if ($LASTEXITCODE -ne 0) { throw "Unreal evidence manifest creation failed" }
& $Python (Join-Path $RepoRoot "scripts\verify_unreal_engine_evidence.py") $EvidenceRoot
if ($LASTEXITCODE -ne 0) { throw "Unreal evidence verification failed" }
Write-Host "Unreal Engine Win64 gate passed: $EvidenceRoot"
