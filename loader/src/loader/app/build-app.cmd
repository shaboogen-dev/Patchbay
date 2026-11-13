@echo off
setlocal EnableExtensions
set "HERE=%~dp0"
set "BUILD=%HERE%build"
if exist "%BUILD%" rmdir /s /q "%BUILD%"
mkdir "%BUILD%\classes"
dir /s /b "%HERE%src\*.java" > "%BUILD%\sources.txt"
javac --release 21 -d "%BUILD%\classes" @"%BUILD%\sources.txt"
if errorlevel 1 exit /b %ERRORLEVEL%
>"%BUILD%\manifest.mf" echo Main-Class: dev.bitwigre.bml.BmlControlApp
jar --create --file "%HERE%bml-control.jar" --manifest "%BUILD%\manifest.mf" -C "%BUILD%\classes" .
exit /b %ERRORLEVEL%
