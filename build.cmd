@echo off
rem ============================================================================
rem  S1A5 build script
rem
rem    build.cmd    package the server
rem
rem  Output: target\s1a5-0.1.0-SNAPSHOT-all.jar  (fat jar, java -jar ready)
rem
rem  The running server locks the jar. Close run.cmd before building.
rem
rem  KEEP THIS FILE PURE ASCII. cmd.exe mis-parses batch files that contain
rem  non-ASCII bytes and silently stops executing them.
rem ============================================================================
setlocal

set "APP_HOME=%~dp0"
set "JAR=%APP_HOME%target\s1a5-0.1.0-SNAPSHOT-all.jar"

where mvn >nul 2>nul
if errorlevel 1 (
    echo [ERROR] mvn not found on PATH
    if not defined ACT5_NO_PAUSE pause
    exit /b 1
)

tasklist /fi "imagename eq java.exe" 2>nul | find /i "java.exe" >nul
if not errorlevel 1 (
    echo [WARN ] java.exe is running -- if it is our server it holds the jar.
    echo         Close run.cmd first.
    echo.
)

echo [INFO] JDK   : %JAVA_HOME%
for /f "delims=" %%v in ('mvn -v 2^>nul ^| findstr /b /c:"Apache Maven"') do echo [INFO] Maven : %%v
echo.

rem Clean build: delete target\, then package offline (-o). The local Maven repo
rem has every build dependency except maven-clean-plugin, so target\ is deleted
rem here. Extra arguments go straight to Maven.
if exist "%APP_HOME%target" rmdir /s /q "%APP_HOME%target"
if exist "%APP_HOME%target" (
    echo [ERROR] cannot delete target\ -- the server is still running and holds the jar
    if not defined ACT5_NO_PAUSE pause
    exit /b 1
)
call mvn -o -f "%APP_HOME%pom.xml" package %*
if errorlevel 1 (
    echo.
    echo [ERROR] build failed
    if not defined ACT5_NO_PAUSE pause
    exit /b 1
)

echo.
echo [OK] artifacts:
dir /b "%APP_HOME%target\*.jar"
echo.
for %%f in ("%JAR%") do echo [OK] jar timestamp: %%~tf
echo.
echo Next: run.cmd    start the server
echo.
if not defined ACT5_NO_PAUSE pause
exit /b 0
