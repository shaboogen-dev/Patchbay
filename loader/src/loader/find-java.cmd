@echo off
rem Finds the Java that runs the window (Patchbay) and the preparer, and sets JAVA in the caller.
rem   call find-java.cmd window|console <BML root> [<bin folder of the Bitwig being prepared for>]
rem window: javaw.exe (no console window); console: java.exe. JAVA is empty when nothing is found.
rem Order: BML_JAVA; the Bitwig being prepared for; the Bitwig chosen in the window's settings; the
rem Bitwig in its default place; JAVA_HOME; PATH. A Bitwig runtime counts only when its release file
rem lists java.desktop (Swing); JAVA_HOME and PATH only when they are Java 21 or later.
setlocal EnableExtensions DisableDelayedExpansion
set "MODE=%~1"
set "ROOT=%~2"
set "BIN=%~3"
set "FOUND="
set "EXE=java.exe"
if /i "%MODE%"=="window" set "EXE=javaw.exe"
if defined BML_JAVA call :explicit "%BML_JAVA%"
if defined FOUND goto done
if defined BIN call :bitwig "%BIN%\.."
if defined FOUND goto done
call :configured
if defined FOUND goto done
if defined ProgramFiles call :bitwig "%ProgramFiles%\Bitwig Studio"
if defined FOUND goto done
if defined JAVA_HOME call :system "%JAVA_HOME%\bin\java.exe"
if defined FOUND goto done
for %%I in (java.exe) do if not "%%~$PATH:I"=="" call :system "%%~$PATH:I"
:done
endlocal & set "JAVA=%FOUND%"
exit /b 0

:explicit
if not exist "%~1" exit /b 0
set "FOUND=%~f1"
if /i "%MODE%"=="window" if exist "%~dp1javaw.exe" set "FOUND=%~dp1javaw.exe"
exit /b 0

:bitwig
if not exist "%~1\jre\bin\%EXE%" exit /b 0
findstr /c:"java.desktop" "%~1\jre\release" >nul 2>&1 || exit /b 0
for %%J in ("%~1\jre\bin\%EXE%") do set "FOUND=%%~fJ"
exit /b 0

:configured
set "SETTINGS=%LOCALAPPDATA%\BML\bml.properties"
if exist "%ROOT%\config\portable" set "SETTINGS=%ROOT%\config\bml.properties"
if not exist "%SETTINGS%" set "SETTINGS=%ROOT%\config\bml.properties"
if not exist "%SETTINGS%" exit /b 0
set "CONF="
for /f "usebackq tokens=1,* delims==" %%A in ("%SETTINGS%") do if "%%A"=="bitwig.executable" set "CONF=%%B"
if not defined CONF exit /b 0
rem Java properties escape \ and : as \\ and \:
set "CONF=%CONF:\\=\%"
set "CONF=%CONF:\:=:%"
for %%F in ("%CONF%") do call :bitwig "%%~dpF."
exit /b 0

:system
if not exist "%~1" exit /b 0
set "VER="
set "MAJOR="
for /f "tokens=3" %%V in ('call "%~1" -version 2^>^&1 ^| findstr /i /c:" version "') do if not defined VER set "VER=%%~V"
if not defined VER exit /b 0
for /f "delims=.-+" %%M in ("%VER%") do set "MAJOR=%%M"
if not defined MAJOR exit /b 0
if %MAJOR% LSS 21 exit /b 0
set "FOUND=%~f1"
if /i "%MODE%"=="window" if exist "%~dp1javaw.exe" set "FOUND=%~dp1javaw.exe"
exit /b 0
