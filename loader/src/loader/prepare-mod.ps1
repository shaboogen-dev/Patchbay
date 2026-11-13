# Compatibility entry point: JavaPreparer is the single authoritative
# implementation for external map packs on Windows and other platforms.
param(
    [string]$BitwigBin = "$env:ProgramFiles\Bitwig Studio\bin",
    [string]$ModsRoot = ""
)
$ErrorActionPreference='Stop'
$loader=Split-Path -Parent $MyInvocation.MyCommand.Path
# The order of find-java.cmd: BML_JAVA, the Bitwig being prepared for (its runtime must list
# java.desktop), JAVA_HOME, then java.exe on PATH.
$java='java.exe'
$bitwigJava=Join-Path (Split-Path -Parent $BitwigBin) 'jre\bin\java.exe'
$bitwigRelease=Join-Path (Split-Path -Parent $BitwigBin) 'jre\release'
if($env:BML_JAVA -and (Test-Path -LiteralPath $env:BML_JAVA)){$java=$env:BML_JAVA}
elseif((Test-Path -LiteralPath $bitwigJava) -and (Select-String -LiteralPath $bitwigRelease -SimpleMatch 'java.desktop' -Quiet -ErrorAction SilentlyContinue)){$java=$bitwigJava}
elseif($env:JAVA_HOME){
    $candidate=Join-Path $env:JAVA_HOME 'bin\java.exe'
    if(Test-Path -LiteralPath $candidate){$java=$candidate}
}
$argsList=@('-cp',(Join-Path $loader 'app\bml-control.jar'),
    'dev.bitwigre.bml.JavaPreparer','--loader-root',$loader,'--bitwig-bin',$BitwigBin)
if(-not [string]::IsNullOrWhiteSpace($ModsRoot)){$argsList+=@('--mods-root',$ModsRoot)}
& $java @argsList
exit $LASTEXITCODE
