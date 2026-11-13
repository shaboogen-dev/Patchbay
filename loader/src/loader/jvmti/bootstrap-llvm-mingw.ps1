$ErrorActionPreference = 'Stop'

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolchainRoot = Join-Path $Here 'toolchain'
$Archive = Join-Path $Here 'llvm-mingw-20260922-ucrt-x86_64.zip'
$Url = 'https://github.com/mstorsjo/llvm-mingw/releases/download/20260922/llvm-mingw-20260922-ucrt-x86_64.zip'
$ExpectedSha256 = 'e3ad77d117a4bea19a7a3b333341824d79a5a371004a10e25b8504e7b3047666'

function Find-Compiler {
    if (-not (Test-Path $ToolchainRoot)) { return $null }
    $compiler = Get-ChildItem -Path $ToolchainRoot -Recurse -File -Filter 'x86_64-w64-mingw32-clang++.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $compiler) {
        $compiler = Get-ChildItem -Path $ToolchainRoot -Recurse -File -Filter 'clang++.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    return $compiler
}

$existing = Find-Compiler
if ($existing) {
    Write-Host "Portable LLVM-MinGW already present: $($existing.FullName)"
    exit 0
}

New-Item -ItemType Directory -Force -Path $ToolchainRoot | Out-Null

Write-Host 'No native C++ compiler is installed.'
Write-Host 'Downloading portable LLVM-MinGW 20260922 (UCRT, x86_64 host)...'
Write-Host $Url

if (Test-Path $Archive) {
    $actual = (Get-FileHash -Algorithm SHA256 -Path $Archive).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256) {
        Write-Host 'Existing cached archive has the wrong SHA-256; deleting it.'
        Remove-Item -Force $Archive
    }
}

if (-not (Test-Path $Archive)) {
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Archive
}

$actual = (Get-FileHash -Algorithm SHA256 -Path $Archive).Hash.ToLowerInvariant()
if ($actual -ne $ExpectedSha256) {
    throw "LLVM-MinGW SHA-256 mismatch. Expected $ExpectedSha256 but got $actual. The archive was not extracted."
}
Write-Host "SHA-256 verified: $actual"

Write-Host 'Extracting portable toolchain...'
# Extract to a temporary sibling so a failed extraction cannot leave a half-valid toolchain.
$Temp = Join-Path $Here 'toolchain.extracting'
if (Test-Path $Temp) { Remove-Item -Recurse -Force $Temp }
New-Item -ItemType Directory -Force -Path $Temp | Out-Null
Expand-Archive -LiteralPath $Archive -DestinationPath $Temp -Force

if (Test-Path $ToolchainRoot) { Remove-Item -Recurse -Force $ToolchainRoot }
Move-Item -Path $Temp -Destination $ToolchainRoot

$compiler = Find-Compiler
if (-not $compiler) {
    throw 'LLVM-MinGW extracted, but x86_64-w64-mingw32-clang++.exe/clang++.exe was not found.'
}

# The archive is no longer needed after a verified extraction.
Remove-Item -Force $Archive
Write-Host "Portable compiler ready: $($compiler.FullName)"
