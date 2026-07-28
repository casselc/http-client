#requires -version 5
<#
.SYNOPSIS
  Run the portable Schannel contracts and a real native Windows TLS loopback
  gate against a self-signed .NET SslStream origin.

.DESCRIPTION
  The child fixture publishes its ephemeral port over redirected stdout. The
  parent blocks on ReadLine rather than sleeping or polling, so readiness is an
  observed event. Three ordered connections prove secure rejection, explicit
  trust-all success, and secure rejection again (no validation-mode leakage).
#>
param(
  [string]$ProjectPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [Parameter(Mandatory = $true)]
  [string]$RuntimePath,
  [Parameter(Mandatory = $true)]
  [string]$ChezExe,
  [string]$GitLibsPath = "",
  [string]$ShellExe = "",
  [ValidateSet("x86-64", "aarch64")]
  [string]$ExpectedArch = "x86-64",
  [int]$TimeoutSeconds = 600
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ChezExe)) {
  throw "test-windows-schannel.ps1: scheme.exe not found at $ChezExe"
}
if (-not (Test-Path (Join-Path $RuntimePath "host\chez\cli.ss"))) {
  throw "test-windows-schannel.ps1: Jolt runtime not found under $RuntimePath"
}
if ($TimeoutSeconds -le 0) {
  throw "test-windows-schannel.ps1: TimeoutSeconds must be positive"
}

if ([string]::IsNullOrWhiteSpace($ShellExe)) {
  $candidates = @(
    "$env:ProgramFiles\Git\bin\sh.exe",
    "${env:ProgramFiles(x86)}\Git\bin\sh.exe",
    "C:\Program Files\Git\bin\sh.exe"
  ) | Where-Object { $_ -and (Test-Path $_) }
  if ($candidates) {
    $ShellExe = $candidates[0]
  }
  else {
    $command = Get-Command sh -ErrorAction SilentlyContinue
    if ($command) {
      $ShellExe = $command.Source
    }
  }
}
if ([string]::IsNullOrWhiteSpace($ShellExe) -or -not (Test-Path $ShellExe)) {
  throw "test-windows-schannel.ps1: sh.exe not found"
}

$env:JOLT_PWD = $ProjectPath
$env:JOLT_AOT_CACHE = "0"
$env:JOLT_VERSION = "dev"
$env:JOLT_SH = (Resolve-Path $ShellExe).Path
$env:JOLT_EXPECTED_ARCH = $ExpectedArch

if ([string]::IsNullOrWhiteSpace($GitLibsPath)) {
  $GitLibsPath = Join-Path $ProjectPath ".jolt-cache\gitlibs"
}
if (-not (Test-Path $GitLibsPath)) {
  $null = New-Item -ItemType Directory -Force -Path $GitLibsPath
}
$env:JOLT_GITLIBS = (Resolve-Path $GitLibsPath).Path

function Invoke-Jolt {
  param(
    [string]$Phase,
    [string]$Alias
  )

  Write-Host $Phase
  $process = Start-Process `
    -FilePath $ChezExe `
    -ArgumentList @("--script", "host\chez\cli.ss", $Alias) `
    -NoNewWindow `
    -PassThru
  $null = $process.Handle
  if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
    try { $process.Kill(); $process.WaitForExit() } catch {}
    throw "$Phase timed out after $TimeoutSeconds seconds"
  }
  $exitCode = $process.ExitCode
  if ($null -eq $exitCode) {
    throw "$Phase observed no child exit code"
  }
  if ($exitCode -ne 0) {
    throw "$Phase failed with exit code $exitCode"
  }
}

$fixtureScript = Join-Path $ProjectPath `
  "tools\schannel-loopback-fixture.ps1"
$pfx = Join-Path $ProjectPath `
  "test\resources\schannel-test.pfx.b64"
$fixturePowerShell = Get-Command pwsh.exe -ErrorAction SilentlyContinue
if (-not $fixturePowerShell) {
  throw "test-windows-schannel.ps1: pwsh.exe is required for SslStream.ShutdownAsync"
}
$fixtureInfo = New-Object Diagnostics.ProcessStartInfo
$fixtureInfo.FileName = $fixturePowerShell.Source
$fixtureInfo.Arguments = (
  "-NoProfile -ExecutionPolicy Bypass -File `"$fixtureScript`" " +
  "-PfxBase64Path `"$pfx`"")
$fixtureInfo.UseShellExecute = $false
$fixtureInfo.RedirectStandardOutput = $true
$fixtureInfo.RedirectStandardError = $true
$fixtureInfo.CreateNoWindow = $true
$fixture = New-Object Diagnostics.Process
$fixture.StartInfo = $fixtureInfo

Write-Host "Start native TLS loopback fixture"
if (-not $fixture.Start()) {
  throw "test-windows-schannel.ps1: failed to start TLS fixture"
}

$failure = $null
try {
  # A blocking pipe read is the readiness gate: no sleep, retry, or guessed
  # startup interval.
  $portLine = $fixture.StandardOutput.ReadLine()
  if ($portLine -notmatch '^PORT=([0-9]+)$') {
    $stderr = $fixture.StandardError.ReadToEnd()
    throw "TLS fixture did not publish a port: '$portLine' $stderr"
  }
  $env:JOLT_SCHANNEL_PORT = $Matches[1]
  Write-Host "  port = $env:JOLT_SCHANNEL_PORT"
  Write-Host "  arch = $env:JOLT_EXPECTED_ARCH"

  Push-Location $RuntimePath
  try {
    Invoke-Jolt `
      -Phase "Run portable Schannel contracts" `
      -Alias "-M:schannel-contract-test"
    Invoke-Jolt `
      -Phase "Run native Schannel loopback gate" `
      -Alias "-M:schannel-runtime-test"
  }
  finally {
    Pop-Location
  }
}
catch {
  $failure = $_
}
finally {
  if ($failure -and -not $fixture.HasExited) {
    try { $fixture.Kill(); $fixture.WaitForExit() } catch {}
  }
  if (-not $fixture.HasExited) {
    if (-not $fixture.WaitForExit($TimeoutSeconds * 1000)) {
      try { $fixture.Kill(); $fixture.WaitForExit() } catch {}
      if (-not $failure) {
        $failure = "TLS fixture timed out after $TimeoutSeconds seconds"
      }
    }
  }

  $fixtureOutput = $fixture.StandardOutput.ReadToEnd()
  $fixtureError = $fixture.StandardError.ReadToEnd()
  if (-not [string]::IsNullOrWhiteSpace($fixtureOutput)) {
    Write-Host $fixtureOutput
  }
  if (-not [string]::IsNullOrWhiteSpace($fixtureError)) {
    [Console]::Error.WriteLine($fixtureError)
  }
  if ($fixture.HasExited -and $fixture.ExitCode -ne 0 -and -not $failure) {
    $failure = "TLS fixture failed with exit code $($fixture.ExitCode)"
  }
  if (-not $fixture.HasExited) {
    try { $fixture.Kill(); $fixture.WaitForExit() } catch {}
  }
  $fixture.Dispose()
}

if ($failure) {
  throw $failure
}
