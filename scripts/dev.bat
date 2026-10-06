@echo off
REM ==============================================================================
REM ShellMind one-command startup (Windows)
REM Flow: clear caches -> check shellmind-server -> detect/build jar -> start backend -> start desktop
REM Usage: scripts\dev.bat [--clean] [--rebuild] [--no-server] [--no-client]
REM   --clean      Deep clean (remove node_modules\.vite then npm install)
REM   --rebuild    Force rebuild the server jar
REM   --no-server  Skip backend
REM   --no-client  Skip desktop
REM ==============================================================================
setlocal enabledelayedexpansion
chcp 65001 >nul

set "ROOT_DIR=%~dp0.."
for %%i in ("%ROOT_DIR%") do set "ROOT_DIR=%%~fi"
set "CLIENT_DIR=%ROOT_DIR%\client"
set "SERVER_DIR=%ROOT_DIR%\server"

REM Load root .env if present (ignore # comments) so the backend can read SHELLMIND_*
if exist "%ROOT_DIR%\.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ROOT_DIR%\.env") do if not "%%a"=="" set "%%a=%%b"
)
set "PORT=8091"
set "LOG_FILE=%TEMP%\shellmind-server-dev.log"

set "CLEAN=0"
set "REBUILD=0"
set "START_SERVER=1"
set "START_CLIENT=1"

:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="--clean"     set "CLEAN=1"
if /i "%~1"=="--rebuild"   set "REBUILD=1"
if /i "%~1"=="--no-server" set "START_SERVER=0"
if /i "%~1"=="--no-client" set "START_CLIENT=0"
shift
goto parse_args
:args_done

echo ==============================================
echo   ShellMind one-command start (dev)
echo   Client dir: %CLIENT_DIR%
echo ==============================================

REM ---------- 1. Cache cleanup ----------
echo [INFO] Clearing frontend cache...
if exist "%CLIENT_DIR%\node_modules\.vite" rmdir /s /q "%CLIENT_DIR%\node_modules\.vite"
if exist "%CLIENT_DIR%\tsconfig.tsbuildinfo" del /q "%CLIENT_DIR%\tsconfig.tsbuildinfo"
if exist "%CLIENT_DIR%\src-tauri\tsconfig.tsbuildinfo" del /q "%CLIENT_DIR%\src-tauri\tsconfig.tsbuildinfo"

REM Kill leftover processes on 8091 (backend) and 5173 (Vite)
for %%p in (8091 5173) do (
    for /f "tokens=5" %%q in ('netstat -ano ^| findstr ":%%p " ^| findstr "LISTENING"') do (
        echo [WARN] Port %%p is in use (PID: %%q); stopping...
        taskkill /f /pid %%q >nul 2>&1
    )
)
echo [ OK ] Cache cleared

if "%CLEAN%"=="1" (
    echo [INFO] Deep clean: reinstalling dependencies...
    pushd "%CLIENT_DIR%"
    call npm install || goto :npm_fail
    popd
    echo [ OK ] Dependencies installed
)

REM ---------- 2. Check server ----------
if not exist "%SERVER_DIR%\pom.xml" (
    echo.
    echo [FAIL] Server project not found: %SERVER_DIR% (is the repo complete?)
    echo.
    pause
    exit /b 1
)
echo [ OK ] Found server project: %SERVER_DIR%

REM ---------- 3. Tooling ----------
where node >nul 2>&1 || (echo [FAIL] node not found. Install Node.js ^(^>=18^) first. & pause & exit /b 1)
where npm  >nul 2>&1 || (echo [FAIL] npm not found & pause & exit /b 1)

REM ---------- 4. Java runtime ----------
set "JAVA_BIN=java"
set "BUNDLED_JRE=%CLIENT_DIR%\resources\agent\runtime"
where java >nul 2>&1
if errorlevel 1 (
    if exist "%BUNDLED_JRE%\bin\java.exe" (
        set "JAVA_BIN=%BUNDLED_JRE%\bin\java.exe"
        set "PATH=%BUNDLED_JRE%\bin;%PATH%"
        echo [ OK ] Using bundled JRE: %BUNDLED_JRE%
    ) else (
        echo [FAIL] Java not found (need 17+).
        echo   Install Temurin 17: https://adoptium.net/
        echo   Or run npm run agent:runtime in the client project to download a bundled JRE.
        pause
        exit /b 1
    )
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%v"
echo [ OK ] Java: !JAVA_VER!

REM ---------- 5. Detect jar ----------
set "JAR_FILE=%SERVER_DIR%\shellmind-server-app\target\shellmind-server.jar"
set "NEED_BUILD=0"

if "%REBUILD%"=="1" (
    set "NEED_BUILD=1"
    echo [INFO] --rebuild set; forcing jar rebuild
) else if not exist "%JAR_FILE%" (
    set "NEED_BUILD=1"
    echo [INFO] Jar not found; a build is required
) else (
    REM Detect source newer than the jar
    for /f %%d in ('dir /b /o-d "%SERVER_DIR%\shellmind-server-app\src\main\java" "%SERVER_DIR%\pom.xml" 2^>nul') do rem
    for /f "delims=" %%f in ('powershell -NoProfile -Command "$jar=(Get-Item '%JAR_FILE%').LastWriteTime; $newer=Get-ChildItem -Path '%SERVER_DIR%' -Recurse -Include *.java,pom.xml,*.yml -File ^| Where-Object { $_.FullName -notmatch '\\\\target\\\\' -and $_.LastWriteTime -gt $jar } ^| Select-Object -First 1; if($newer){Write-Output 'YES'}else{Write-Output 'NO'}"') do set "SRC_NEWER=%%f"
    if "!SRC_NEWER!"=="YES" (
        set "NEED_BUILD=1"
        echo [INFO] Jar is stale (source updated); rebuilding
    ) else (
        echo [ OK ] Jar is up to date: %JAR_FILE%
    )
)

if "%NEED_BUILD%"=="1" (
    where mvn >nul 2>&1
    if errorlevel 1 (
        if exist "%JAR_FILE%" (
            echo [WARN] mvn not found; skipping build and using existing jar
        ) else (
            echo [FAIL] mvn not found and no jar is present. Install Maven: https://maven.apache.org/
            pause
            exit /b 1
        )
    ) else (
        echo [INFO] Building server jar ^(mvn -DskipTests package; first run can be slow^)...
        pushd "%SERVER_DIR%"
        call mvn -q -DskipTests package
        if errorlevel 1 (
            popd
            echo [FAIL] Server build failed; check shellmind-server
            pause
            exit /b 1
        )
        popd
        echo [ OK ] Server build complete
    )
)

REM ---------- 6. Start backend ----------
if "%START_SERVER%"=="1" (
    echo [INFO] Starting backend ^(profile=local, port=%PORT%^). Spring Boot usually takes 40-60s...
    echo [INFO] Log file: %LOG_FILE%
    pushd "%SERVER_DIR%"
    start "shellmind-server" /min cmd /c ""%JAVA_BIN%" -jar "%JAR_FILE%" --spring.profiles.active=local --server.port=%PORT% > "%LOG_FILE%" 2>&1"
    popd

    set "READY=0"
    for /l %%i in (1,1,120) do (
        if "!READY!"=="0" (
            powershell -NoProfile -Command "(Test-NetConnection -ComputerName 127.0.0.1 -Port %PORT% -WarningAction SilentlyContinue).TcpTestSucceeded" 2>nul | findstr /i "True" >nul && set "READY=1"
            if "!READY!"=="0" (
                <nul set /p "=."
                timeout /t 2 /nobreak >nul
            )
        )
    )

    if "!READY!"=="1" (
        echo.
        echo [ OK ] Backend ready: http://localhost:%PORT%
    ) else (
        echo.
        echo [FAIL] Timed out waiting for backend (~240s). See logs: %LOG_FILE%
        pause
        exit /b 1
    )
) else (
    echo [WARN] Skipping backend (--no-server)
)

REM ---------- 7. Start desktop (Tauri) ----------
if not exist "%CLIENT_DIR%\node_modules" (
    echo [INFO] node_modules missing; installing dependencies...
    pushd "%CLIENT_DIR%"
    call npm install || goto :npm_fail
    popd
    echo [ OK ] Dependencies installed
)

where cargo >nul 2>&1
if errorlevel 1 (
    echo [FAIL] cargo (Rust) not found; the desktop app needs a Rust toolchain.
    echo   Install: download rustup-init.exe from https://rustup.rs/
    pause
    exit /b 1
)

if "%START_CLIENT%"=="1" (
    echo [INFO] Starting desktop app ^(Tauri dev^). The ShellMind window should open shortly...
    echo [INFO] After you close the window, press Enter to stop the backend
    pushd "%CLIENT_DIR%"
    call npm run tauri dev
    popd
    echo [ OK ] Desktop app exited
) else (
    echo [ OK ] Done (--no-client; desktop app was not started)
)
goto :eof

:npm_fail
echo [FAIL] npm install failed; check the network or Node version
popd
pause
exit /b 1
