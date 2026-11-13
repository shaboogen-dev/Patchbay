param([Parameter(Mandatory=$true)][string]$BitwigJar)
# Diagnostic only: reads installed user-owned JAR, never writes to it.
# Print bounded method bytecode into BML's debug preparation log, so it is
# collected with the ordinary session ZIP. No obfuscated names are guessed.
$ErrorActionPreference = 'Stop'
function Emit([string]$s) { Write-Output ("[BML C32 COPY PATH] " + $s) }
$javap = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\javap.exe') }
if ($env:ProgramFiles) { $candidates += (Join-Path $env:ProgramFiles 'Java\jdk-25.0.4.1\bin\javap.exe') }
foreach ($candidate in $candidates) {
    if (Test-Path -LiteralPath $candidate) { $javap = $candidate; break }
}
if (-not $javap) {
    $command = Get-Command 'javap.exe' -ErrorAction SilentlyContinue
    if ($command) { $javap = [string]$command.Source }
}
if (-not $javap) { Emit 'UNAVAILABLE javap.exe not found (nonfatal)'; return }
if (-not (Test-Path -LiteralPath $BitwigJar)) { Emit 'UNAVAILABLE bitwig.jar not found (nonfatal)'; return }
Emit 'BEGIN installed 6.1.3 method code; no Bitwig classes are redistributed'
function Inspect([string]$owner,[string[]]$names) {
    try {
        $output = @(& $javap -c -p -s -classpath $BitwigJar $owner 2>&1 | ForEach-Object { [string]$_ })
        if ($LASTEXITCODE -ne 0) { Emit "ERROR javap $owner exit=$LASTEXITCODE"; return }
        $n = 0
        $found = 0
        for ($i=0; $i -lt $output.Count; ++$i) {
            $line = $output[$i]
            if ($line -notmatch '^\s+(?:(?:public|private|protected|static|final|synchronized|native|abstract|strictfp)\s+)+.*\(') { continue }
            $wanted = $false
            foreach ($name in $names) { if ($line -match ("\b" + [regex]::Escape($name) + "\(")) { $wanted = $true; break } }
            if (-not $wanted) { continue }
            # The precise overloaded descriptor is essential: don't print unrelated overloads.
            $descriptor = if ($i+1 -lt $output.Count) { $output[$i+1] } else { '' }
            $rangeMethod = ($owner -eq 'Unc' -and $descriptor -match '\(Ljava/util/List;DDLGg3;\)V')
            $callerMethod = ($owner -eq 'Gg3' -and $line -match '\bXYc\(\)')
            if (-not $rangeMethod -and -not $callerMethod) { continue }
            ++$found
            Emit ("METHOD " + $owner + ' ' + $line.Trim() + ' ' + $descriptor.Trim())
            $n=0
            for ($j=$i+2; $j -lt $output.Count -and $n -lt 190; ++$j) {
                $item=$output[$j]
                if ($item -match '^\s+(?:(?:public|private|protected|static|final|synchronized|native|abstract|strictfp)\s+)+.*\(' -or $item -match '^\}') {break}
                # Method disassembly includes code, switch tables, exception and stack-map details.
                if (-not [string]::IsNullOrWhiteSpace($item)) {
                    Emit ($item.TrimEnd())
                    ++$n
                }
            }
            Emit ("END_METHOD " + $owner + " lines=$n")
        }
        Emit ("OWNER_SUMMARY $owner found=$found")
    } catch { Emit ("ERROR inspecting $owner : " + $_.Exception.Message) }
}
Inspect 'Unc' @('JQk','kpO','HEY')
Inspect 'Gg3' @('XYc')
Emit 'END'
