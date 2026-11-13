@echo off
setlocal EnableExtensions
rem Windows' own tools (findstr, tasklist, PowerShell) are found even when PATH doesn't list them.
if not defined SystemRoot set "SystemRoot=%SystemDrive%\Windows"
set "PATH=%SystemRoot%\System32;%SystemRoot%;%SystemRoot%\System32\WindowsPowerShell\v1.0;%PATH%"
if not defined PATHEXT set "PATHEXT=.COM;.EXE;.BAT;.CMD"
set "HERE=%~dp0"
set "BITWIG_BIN=%~1"
set "MODS_ROOT=%~2"
if not defined BITWIG_BIN set "BITWIG_BIN=%ProgramFiles%\Bitwig Studio\bin"
for %%I in ("%HERE%..") do set "ROOT=%%~fI"
rem The Java the window runs on (it passes it as BML_JAVA), else the runtime of the Bitwig being
rem prepared for, then the order in find-java.cmd.
call "%HERE%find-java.cmd" console "%ROOT%" "%BITWIG_BIN%"
if not defined JAVA goto nojava
if defined MODS_ROOT (
  "%JAVA%" -cp "%HERE%app\bml-control.jar" dev.bitwigre.bml.JavaPreparer --loader-root "%HERE%." --bitwig-bin "%BITWIG_BIN%" --mods-root "%MODS_ROOT%"
) else (
  "%JAVA%" -cp "%HERE%app\bml-control.jar" dev.bitwigre.bml.JavaPreparer --loader-root "%HERE%." --bitwig-bin "%BITWIG_BIN%"
)
exit /b %ERRORLEVEL%
:nojava
echo Java 21+ not found: install Bitwig Studio, or set BML_JAVA or JAVA_HOME 1>&2
exit /b 3
