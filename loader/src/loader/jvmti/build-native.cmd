@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "HERE=%~dp0"
set "SRC=%HERE%src\bitwig_jvmti_tracer.cpp"
set "OUTDIR=%HERE%bin"
set "OUT=%OUTDIR%\bitwig-jvmti-tracer.dll"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

if defined JAVA_HOME if exist "%JAVA_HOME%\include\jvmti.h" goto have_java
for %%I in (jcmd.exe) do set "JCMD_EXE=%%~$PATH:I"
if defined JCMD_EXE (
  for %%I in ("%JCMD_EXE%\..\..") do set "JAVA_HOME=%%~fI"
)
if not exist "%JAVA_HOME%\include\jvmti.h" (
  echo ERROR: Could not find a full JDK with include\jvmti.h.
  echo Set JAVA_HOME to the JDK used by the Inspector and try again.
  exit /b 2
)

:have_java
where cl.exe >nul 2>nul && goto build_msvc
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" (
  for /f "usebackq tokens=*" %%I in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSROOT=%%I"
)
if defined VSROOT if exist "%VSROOT%\VC\Auxiliary\Build\vcvars64.bat" (
  call "%VSROOT%\VC\Auxiliary\Build\vcvars64.bat" >nul
  where cl.exe >nul 2>nul && goto build_msvc
)
where g++.exe >nul 2>nul && goto build_mingw
where x86_64-w64-mingw32-clang++.exe >nul 2>nul && (
  set "LLVM_MINGW_CXX=x86_64-w64-mingw32-clang++.exe"
  goto build_llvm_mingw
)

rem No installed compiler: bootstrap the pinned portable LLVM-MinGW release locally.
for /r "%HERE%toolchain" %%I in (x86_64-w64-mingw32-clang++.exe) do if not defined LLVM_MINGW_CXX set "LLVM_MINGW_CXX=%%~fI"
if defined LLVM_MINGW_CXX goto build_llvm_mingw

echo No Windows C++ compiler found. Bootstrapping a portable LLVM-MinGW toolchain...
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%bootstrap-llvm-mingw.ps1"
if errorlevel 1 (
  echo.
  echo ERROR: Portable compiler bootstrap failed.
  echo You can alternatively install Visual Studio Build Tools with Desktop development with C++.
  exit /b 3
)
for /r "%HERE%toolchain" %%I in (x86_64-w64-mingw32-clang++.exe) do if not defined LLVM_MINGW_CXX set "LLVM_MINGW_CXX=%%~fI"
if not defined LLVM_MINGW_CXX (
  for /r "%HERE%toolchain" %%I in (clang++.exe) do if not defined LLVM_MINGW_CXX set "LLVM_MINGW_CXX=%%~fI"
)
if not defined LLVM_MINGW_CXX (
  echo ERROR: Portable LLVM-MinGW was extracted but no C++ compiler could be located.
  exit /b 3
)
goto build_llvm_mingw

:build_msvc
echo Building JVMTI tracer with MSVC...
pushd "%OUTDIR%"
cl /nologo /EHsc /std:c++17 /O2 /MT /LD ^
  /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" ^
  "%SRC%" /link /INCREMENTAL:NO /OUT:"%OUT%"
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" exit /b %RC%
goto built

:build_mingw
echo Building JVMTI tracer with installed MinGW-w64...
g++ -std=c++17 -O2 -shared -static-libgcc -static-libstdc++ ^
  -I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32" ^
  "%SRC%" -o "%OUT%"
if errorlevel 1 exit /b %ERRORLEVEL%
goto built

:build_llvm_mingw
echo Building JVMTI tracer with LLVM-MinGW...
echo Compiler: %LLVM_MINGW_CXX%
"%LLVM_MINGW_CXX%" -std=c++17 -O2 -shared -static ^
  -I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32" ^
  "%SRC%" -o "%OUT%"
if errorlevel 1 exit /b %ERRORLEVEL%
goto built

:built
if not exist "%OUT%" (
  echo ERROR: Build reported success but DLL was not created.
  exit /b 4
)
echo.
echo Built: %OUT%
echo JDK headers: %JAVA_HOME%
exit /b 0
