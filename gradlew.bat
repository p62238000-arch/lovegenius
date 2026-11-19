@rem Gradle launcher - dispatches to a local Gradle 8.7 distribution.
@rem Looks for %USERPROFILE%\.gradle\wrapper\dists\gradle-8.7-bin\*\ (extracted),
@rem then GRADLE_HOME, then gradle on PATH.
@if "%DEBUG%"=="" @echo off
setlocal enabledelayedexpansion

set "DIRNAME=%~dp0"
if "%DIRNAME%"=="" set "DIRNAME=.\"

if "%JAVA_HOME%"=="" (
  if exist "C:\Program Files\Java\jdk-21.0.13+9\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.13+9"
  )
)
if "%ANDROID_HOME%"=="" (
  if exist "C:\Android\sdk" set "ANDROID_HOME=C:\Android\sdk"
)

set "GRADLE_BIN="
for /d %%D in ("%USERPROFILE%\.gradle\wrapper\dists\gradle-8.7-bin\*") do (
  if exist "%%D\extract\gradle-8.7\bin\gradle.bat" set "GRADLE_BIN=%%D\extract\gradle-8.7\bin\gradle.bat"
  if exist "%%D\gradle-8.7\bin\gradle.bat" set "GRADLE_BIN=%%D\gradle-8.7\bin\gradle.bat"
)

if not "%GRADLE_BIN%"=="" goto run

if defined GRADLE_HOME (
  if exist "%GRADLE_HOME%\bin\gradle.bat" (
    set "GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat"
    goto run
  )
)

where gradle.bat >NUL 2>&1
if "%ERRORLEVEL%"=="0" (
  set "GRADLE_BIN=gradle.bat"
  goto run
)

echo [gradlew] Gradle 8.7 not found. Run once:
echo     powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\bootstrap_gradle.ps1
exit /b 1

:run
call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%
