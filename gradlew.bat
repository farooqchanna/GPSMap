@echo off
setlocal
set GRADLE_VERSION=8.9
set GRADLE_HOME=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-all\local\gradle-%GRADLE_VERSION%
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo Gradle %GRADLE_VERSION% is not installed in the project cache.
  echo Download https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-all.zip and extract it to:
  echo %GRADLE_HOME%
  exit /b 1
)
call "%GRADLE_HOME%\bin\gradle.bat" -p "%~dp0" %*
