#requires -version 5
<#
.SYNOPSIS
  Compile and run the checked-in Schannel ABI/runtime probe with the installed
  Visual C++ toolchain, then compare it byte-for-byte with committed evidence.

.DESCRIPTION
  The executable is always built for and run on the current Windows host.
  ExpectedArch is independently compared with the process architecture so an
  emulated x86-64 probe cannot publish ARM64 evidence.
#>
param(
  [ValidateSet("x86-64", "aarch64")]
  [string]$ExpectedArch = "x86-64",
  [string]$ProjectPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [string]$OutputPath = "",
  [switch]$NoCompare
)

$ErrorActionPreference = "Stop"

$observedArch = switch ($env:PROCESSOR_ARCHITECTURE.ToUpperInvariant()) {
  "AMD64" { "x86-64" }
  "ARM64" { "aarch64" }
  default { $env:PROCESSOR_ARCHITECTURE.ToLowerInvariant() }
}
if ($observedArch -ne $ExpectedArch) {
  throw "probe-schannel.ps1: expected $ExpectedArch, observed $observedArch"
}

$source = Join-Path $ProjectPath "tools\probe-schannel.c"
if (-not (Test-Path $source)) {
  throw "probe-schannel.ps1: source not found: $source"
}

$vswhere = Join-Path ${env:ProgramFiles(x86)} `
  "Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path $vswhere)) {
  throw "probe-schannel.ps1: vswhere.exe not found: $vswhere"
}

$installation = (& $vswhere -latest -prerelease -products * `
  -property installationPath |
  Select-Object -First 1)
$devcmd = if ([string]::IsNullOrWhiteSpace($installation)) {
  $null
}
else {
  Join-Path $installation "Common7\Tools\VsDevCmd.bat"
}

# The Windows 11 ARM64 image carries the preview VS 2026 line. Older vswhere
# builds have occasionally failed to enumerate that instance even with
# -prerelease, so use the installed VsDevCmd file as the second and final source
# of truth. We still fail closed if there is not exactly a usable toolchain.
if ([string]::IsNullOrWhiteSpace($devcmd) -or -not (Test-Path $devcmd)) {
  $visualStudioRoot = Join-Path $env:ProgramFiles "Microsoft Visual Studio"
  $devcmd = Get-ChildItem $visualStudioRoot -Filter VsDevCmd.bat `
    -File -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($devcmd) -or -not (Test-Path $devcmd)) {
  throw "probe-schannel.ps1: no usable Visual Studio VsDevCmd.bat found"
}

$vcArch = if ($ExpectedArch -eq "aarch64") { "arm64" } else { "amd64" }
$envCommand = "call `"$devcmd`" -no_logo -arch=$vcArch -host_arch=$vcArch >nul && set"
$environmentLines = & $env:ComSpec /d /s /c $envCommand
if ($LASTEXITCODE -ne 0) {
  throw "probe-schannel.ps1: VsDevCmd.bat failed with exit code $LASTEXITCODE"
}
foreach ($line in $environmentLines) {
  $separator = $line.IndexOf("=")
  if ($separator -gt 0) {
    [Environment]::SetEnvironmentVariable(
      $line.Substring(0, $separator),
      $line.Substring($separator + 1),
      "Process")
  }
}

$compiler = Get-Command cl.exe -ErrorAction SilentlyContinue
if (-not $compiler) {
  throw "probe-schannel.ps1: cl.exe was not provided by VsDevCmd.bat"
}

$build = Join-Path $env:TEMP "jolt-http-schannel-probe-$ExpectedArch"
$null = New-Item -ItemType Directory -Force -Path $build
$executable = Join-Path $build "probe-schannel.exe"

Write-Host "Compile Schannel probe"
Write-Host "  source   = $source"
Write-Host "  compiler = $($compiler.Source)"
Write-Host "  arch     = $ExpectedArch"
Push-Location $build
try {
  & $compiler.Source `
    /nologo /TC /W4 /WX /DWIN32_LEAN_AND_MEAN `
    "/Fe:$executable" $source /link Secur32.lib
  if ($LASTEXITCODE -ne 0) {
    throw "probe-schannel.ps1: cl.exe failed with exit code $LASTEXITCODE"
  }
}
finally {
  Pop-Location
}

$lines = & $executable
if ($LASTEXITCODE -ne 0) {
  throw "probe-schannel.ps1: native probe failed with exit code $LASTEXITCODE"
}
$actual = ($lines -join "`n") + "`n"

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
  $OutputPath = Join-Path $ProjectPath `
    "tools\probed\schannel-windows-$ExpectedArch.edn"
}
$outputDirectory = Split-Path -Parent $OutputPath
if (-not (Test-Path $outputDirectory)) {
  $null = New-Item -ItemType Directory -Force -Path $outputDirectory
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

if ($NoCompare) {
  [IO.File]::WriteAllText($OutputPath, $actual, $utf8NoBom)
  Write-Host "wrote $OutputPath"
}
else {
  if (-not (Test-Path $OutputPath)) {
    throw "probe-schannel.ps1: committed evidence not found: $OutputPath"
  }
  $expected = [IO.File]::ReadAllText($OutputPath).Replace("`r`n", "`n")
  if ($expected -ne $actual) {
    Write-Host "Expected:"
    Write-Host $expected
    Write-Host "Actual:"
    Write-Host $actual
    throw "probe-schannel.ps1: native Schannel descriptor drift"
  }
  Write-Host "Schannel descriptor matches $OutputPath"
}
