@echo off
setlocal EnableExtensions
rem Windows' own tools (findstr, tasklist, PowerShell) are found even when PATH doesn't list them.
if not defined SystemRoot set "SystemRoot=%SystemDrive%\Windows"
set "PATH=%SystemRoot%\System32;%SystemRoot%;%SystemRoot%\System32\WindowsPowerShell\v1.0;%PATH%"
if not defined PATHEXT set "PATHEXT=.COM;.EXE;.BAT;.CMD"
for %%I in ("%~dp0.") do set "ROOT=%%~fI"
set "APP=%ROOT%\loader\app\bml-control.jar"
if not exist "%APP%" goto noapp
rem The window runs on Bitwig's own Java (javaw.exe, no console) unless BML_JAVA says otherwise;
rem loader\find-java.cmd has the order.
call "%ROOT%\loader\find-java.cmd" window "%ROOT%"
if not defined JAVA goto nojava
start "Patchbay" "%JAVA%" --enable-native-access=ALL-UNNAMED -Dbml.root="%ROOT%" -jar "%APP%"
exit /b 0
:noapp
echo ERROR: Patchbay's window application was not found:
echo   %APP%
exit /b 2
:nojava
echo ERROR: No Java to run Patchbay was found. Install Bitwig Studio: Patchbay runs on its Java.
echo Or set BML_JAVA or JAVA_HOME to a Java 21 or later.
pause
exit /b 3
