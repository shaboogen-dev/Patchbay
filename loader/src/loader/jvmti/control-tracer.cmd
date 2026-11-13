@echo off
setlocal EnableExtensions
set "HERE=%~dp0"
set "ACTION=%~1"
if /I not "%ACTION%"=="status" if /I not "%ACTION%"=="start" if /I not "%ACTION%"=="stop" (
  echo Syntax: control-tracer.cmd status^|start^|stop
  exit /b 2
)
set "ACTIVE=%HERE%active-session.txt"
if not exist "%ACTIVE%" (
  echo ERROR: No active-session.txt. Launch Bitwig with launch-bitwig-traced.cmd first.
  exit /b 3
)
set "SAVED_PID="
set "CONTROL="
set "TRACE="
for /f "usebackq tokens=1,* delims==" %%A in ("%ACTIVE%") do (
  if /I "%%A"=="pid" set "SAVED_PID=%%B"
  if /I "%%A"=="control" set "CONTROL=%%B"
  if /I "%%A"=="trace" set "TRACE=%%B"
)
if not defined CONTROL (
  echo ERROR: Active session has no control path.
  exit /b 5
)

rem Rediscover the native Bitwig JVM host every time. This avoids stale or malformed
rem saved PIDs and does not depend on jcmd -l, which may omit Bitwig's embedded JVM.
set "PID="
for /f "delims=" %%P in ('powershell -NoProfile -Command "$p=@(Get-Process -Name BitwigStudioApp -ErrorAction SilentlyContinue); if($p.Count -ne 0){[Console]::Write([string]$p[0].Id)}"') do set "PID=%%P"
call :validate_pid
if not defined PID (
  set "PID=%SAVED_PID%"
  call :validate_pid
)
if not defined PID (
  echo ERROR: Could not resolve a numeric BitwigStudioApp PID.
  echo Saved PID in active-session.txt: %SAVED_PID%
  exit /b 4
)

set "JCMD="
for %%I in (jcmd.exe) do set "JCMD=%%~$PATH:I"
if not defined JCMD if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jcmd.exe" set "JCMD=%JAVA_HOME%\bin\jcmd.exe"
if not defined JCMD (
  echo ERROR: jcmd.exe was not found on PATH and JAVA_HOME does not point to a JDK.
  exit /b 6
)

echo Using BitwigStudioApp PID: %PID%
>"%CONTROL%" echo %ACTION%
"%JCMD%" %PID% JVMTI.data_dump
if errorlevel 1 exit /b %ERRORLEVEL%
rem Use a redirected-stdin-safe wait (the tracer control helper may also be launched by BML).
powershell -NoProfile -NonInteractive -Command "Start-Sleep -Milliseconds 1000" >nul
if exist "%CONTROL%" type "%CONTROL%"
if /I "%ACTION%"=="stop" echo Trace: %TRACE%
exit /b 0

:validate_pid
if not defined PID exit /b 0
set "BADPID="
for /f "delims=0123456789" %%X in ("%PID%") do set "BADPID=%%X"
if defined BADPID set "PID="
exit /b 0
