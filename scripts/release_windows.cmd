@echo off
REM ASCII ONLY - do not add Korean text to this file. cmd.exe tracks a byte
REM offset while decoding it, and DBCS characters make that offset drift so
REM comment fragments get executed as commands.
REM
REM Builds, verifies and publishes a release from this machine. All the logic
REM lives in release.sh next to this file (read its header for the steps and the
REM options); this wrapper only finds Git for Windows' bash and runs it, so the
REM Windows and Linux/macOS releases are the same, tested code.
REM
REM   release_windows.cmd --build-only           build + verify only, git untouched
REM   release_windows.cmd 0.5.7 -st              release 0.5.7 without running the tests
REM                                              (-st / --skip-test adds -DskipTests; default: run them)
REM   release_windows.cmd --pr                   main only accepts pull requests: push a release
REM                                              branch + the tag, open the PR, keep the release a draft
REM   release_windows.cmd --help
REM
REM Every run except --build-only asks in this console before building: make a
REM release now? is the version right? It asks once more before committing.
REM The questions cannot be skipped: -y / --yes is rejected. Anything but y/yes,
REM or no input at all, means no, and stopping before the build changes nothing.
REM
REM Needs Git for Windows (it ships bash.exe). WSL's C:\Windows\System32\bash.exe
REM is NOT used: it cannot read Windows paths. To use another bash, set RELEASE_BASH.
REM Publishing also needs the GitHub CLI (winget install GitHub.cli, then gh auth login);
REM without it use --no-publish and upload the four files from target\release\ by hand.
REM
REM No parenthesized blocks around the paths below on purpose: a path such as
REM "C:\Program Files (x86)\Git\bin\bash.exe" would end the block at its ")".
setlocal EnableExtensions

set "BASH="

if not defined RELEASE_BASH goto :from_git
if exist "%RELEASE_BASH%" goto :from_env
echo release: ERROR: RELEASE_BASH points to a file that does not exist: %RELEASE_BASH% 1>&2
exit /b 1
:from_env
set "BASH=%RELEASE_BASH%"
goto :found

:from_git
REM git.exe sits in <Git>\cmd or <Git>\mingw64\bin; bash.exe in <Git>\bin
for /f "delims=" %%G in ('where git 2^>nul') do call :try "%%~dpG..\bin\bash.exe" & call :try "%%~dpG..\..\bin\bash.exe"
if defined BASH goto :found
call :try "%ProgramFiles%\Git\bin\bash.exe"
if defined BASH goto :found
call :try "%ProgramFiles(x86)%\Git\bin\bash.exe"
if defined BASH goto :found
call :try "%LocalAppData%\Programs\Git\bin\bash.exe"
if defined BASH goto :found

echo release: ERROR: Git for Windows (Git Bash) was not found. 1>&2
echo release: Install it from https://git-scm.com/download/win or set RELEASE_BASH to its bash.exe. 1>&2
exit /b 1

:try
if defined BASH exit /b 0
if not exist "%~1" exit /b 0
set "BASH=%~f1"
exit /b 0

:found
REM forward slashes: bash derives the repository root from the script path ($0)
set "SCRIPT=%~dp0release.sh"
set "SCRIPT=%SCRIPT:\=/%"
"%BASH%" "%SCRIPT%" %*
exit /b %ERRORLEVEL%
