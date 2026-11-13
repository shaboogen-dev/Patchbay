@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT=%~dp0"
set "CORE=%ROOT%loader"
set "MODS=%ROOT%mods"
set "BITWIG_EXE=%~1"
if not defined BITWIG_EXE set "BITWIG_EXE=%ProgramFiles%\Bitwig Studio\Bitwig Studio.exe"
if not exist "%BITWIG_EXE%" (
  echo ERROR: Bitwig launcher not found at:
  echo   %BITWIG_EXE%
  echo Pass the full Bitwig Studio.exe path as the first argument if installed elsewhere.
  exit /b 2
)
for %%I in ("%BITWIG_EXE%") do set "BITWIG_ROOT=%%~dpI"
set "BITWIG_BIN=%BITWIG_ROOT%bin"

if "%BML_DEBUG_VERBOSE%"=="1" echo [BML debug] Starting Manifest v2 target resolution, transform preparation and verification...
call "%CORE%\prepare-mod.cmd" "%BITWIG_BIN%" "%MODS%"
if errorlevel 1 exit /b %ERRORLEVEL%
if "%BML_DEBUG_VERBOSE%"=="1" echo [BML debug] Preparation returned successfully; reading runtime metadata...

set "MOD_COUNT="
set "MOD_IDS="
set "TRANSFORM_COUNT="
set "TRACE_TARGET_COUNT="
set "REPLACEMENT_CLASS_COUNT="
for /f "usebackq tokens=1,* delims==" %%A in ("%CORE%\mod-runtime\build-info.txt") do (
  if /I "%%A"=="mod_count" set "MOD_COUNT=%%B"
  if /I "%%A"=="mod_ids" set "MOD_IDS=%%B"
  if /I "%%A"=="transform_count" set "TRANSFORM_COUNT=%%B"
  if /I "%%A"=="trace_target_count" set "TRACE_TARGET_COUNT=%%B"
  if /I "%%A"=="replacement_class_count" set "REPLACEMENT_CLASS_COUNT=%%B"
)
if not defined TRANSFORM_COUNT (
  echo ERROR: Could not read transform_count from loader runtime metadata.
  exit /b 3
)
if not defined TRACE_TARGET_COUNT set "TRACE_TARGET_COUNT=0"
if not defined REPLACEMENT_CLASS_COUNT set "REPLACEMENT_CLASS_COUNT=0"

set "TRACE_ENABLED=%BML_TRACE_ENABLED%"
if not defined TRACE_ENABLED set "TRACE_ENABLED=0"
if /I "%TRACE_ENABLED%"=="true" set "TRACE_ENABLED=1"
if not "%TRACE_ENABLED%"=="1" set "TRACE_ENABLED=0"

rem The agent is required for prepared class replacements even with tracing off.
rem It is also required for an explicit Debug Mode trace-only launch.
set "NEED_AGENT=0"
if not "%REPLACEMENT_CLASS_COUNT%"=="0" set "NEED_AGENT=1"
if "%TRACE_ENABLED%"=="1" if not "%TRACE_TARGET_COUNT%"=="0" set "NEED_AGENT=1"
if "%BML_DEBUG_VERBOSE%"=="1" echo [BML debug] Prepared mods=%MOD_COUNT% transforms=%TRANSFORM_COUNT% replacements=%REPLACEMENT_CLASS_COUNT% trace_targets=%TRACE_TARGET_COUNT%.
if "%BML_DEBUG_VERBOSE%"=="1" if "%NEED_AGENT%"=="1" echo [BML debug] Startup JVMTI agent is required for this launch.
if "%BML_DEBUG_VERBOSE%"=="1" if "%NEED_AGENT%"=="0" echo [BML debug] No startup JVMTI agent is required for this launch.
if "%NEED_AGENT%"=="0" goto launch_plain

set "BITWIG_TRACE_TARGETS=%CORE%\mod-runtime\targets.tsv"
set "BITWIG_CLASS_HOOK_MODE="
set "BITWIG_REPLACEMENT_MAP="
if not "%REPLACEMENT_CLASS_COUNT%"=="0" (
  set "BITWIG_CLASS_HOOK_MODE=file"
  set "BITWIG_REPLACEMENT_MAP=%CORE%\mod-runtime\replacement-map.tsv"
)
set "BITWIG_HELPER_JAR="
if exist "%CORE%\mod-runtime\helper.jar" set "BITWIG_HELPER_JAR=%CORE%\mod-runtime\helper.jar"
set "BML_MOD_CONFIG=%CORE%\mod-runtime\mod-bindings.properties"
set "BITWIG_MOD_LOG_IN_REPORT=1"
set "BML_TRACE_ENABLED=%TRACE_ENABLED%"
if "%BML_DEBUG_VERBOSE%"=="1" echo [BML debug] Handing prepared runtime to the JVMTI launcher...
call "%CORE%\jvmti\launch-bitwig-agent.cmd" "%BITWIG_EXE%"
if errorlevel 1 exit /b %ERRORLEVEL%
echo.
echo Standalone loader active.
echo Mods: %MOD_IDS%
if "%TRACE_ENABLED%"=="1" (
  echo Debug Mode: enabled
) else (
  echo Debug Mode: off
)
exit /b 0

:launch_plain
echo.
if "%BML_DEBUG_VERBOSE%"=="1" echo [BML debug] Launching Bitwig directly without an injected agent.
if "%TRACE_ENABLED%"=="1" if "%TRACE_TARGET_COUNT%"=="0" echo Debug Mode requested, but no trace targets were prepared. Launching without tracing.
echo No startup JVMTI work is required. Launching Bitwig without the BML agent.
powershell -NoProfile -NonInteractive -Command "$ErrorActionPreference = 'Stop'; $psi = New-Object System.Diagnostics.ProcessStartInfo; $psi.FileName = $env:BITWIG_EXE; $psi.WorkingDirectory = [IO.Path]::GetDirectoryName($env:BITWIG_EXE); $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true; $p = [System.Diagnostics.Process]::Start($psi); if ($null -eq $p) { exit 1 }"
if errorlevel 1 (
  echo ERROR: Failed to start Bitwig launcher process.
  exit /b 9
)
echo Standalone loader inert mode: Bitwig started without an injected agent.
exit /b 0
