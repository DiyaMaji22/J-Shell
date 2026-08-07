@echo off
setlocal EnableDelayedExpansion

:: ENABLE ANSI COLORS
for /F %%a in ('echo prompt $E ^| cmd') do set "ESC=%%a"
set "GRN=%ESC%[92m"
set "RED=%ESC%[91m"
set "YEL=%ESC%[93m"
set "CYN=%ESC%[96m"
set "RST=%ESC%[0m"

:: SET SCRIPT DIRECTORY & INITIALIZE
cd /d "%~dp0"
set RESTART_REQUIRED=0

:: Prevent Docker from creating root-owned mount directories
if not exist workspace mkdir workspace

:menu
cls
echo %CYN%===================================================%RST%
echo    J-Shell Quickstart ^& Environment Setup
echo %CYN%===================================================%RST%
echo.
echo [%CYN%1%RST%] Run J-Shell (Docker - Standard Session)
echo [%CYN%2%RST%] Run Tests (Docker - Maven Test Suite)
echo [%CYN%3%RST%] Run Dev Mode (Docker - Live Rebuild from ./src)
echo [%CYN%4%RST%] Run Locally (Native Windows - Requires Java/Maven)
echo.

set /p choice="Select an option (1-4): "
if "%choice%"=="1" set "SVC=jshell" & goto docker_mode
if "%choice%"=="2" set "SVC=jshell-test" & goto docker_mode
if "%choice%"=="3" set "SVC=jshell-dev" & goto docker_mode
if "%choice%"=="4" goto local_mode
echo %RED%[ERROR] Invalid choice. Please select 1-4.%RST%
timeout /t 2 >nul
goto menu

:: DOCKER COMPOSE EXECUTION

:docker_mode
echo.
echo %CYN%[MODE] Containerized Execution: %SVC%%RST%

docker --version >nul 2>&1
if !errorlevel! neq 0 (
    echo %RED%[MISSING] Docker not found.%RST%
    set /p install_docker="Install Docker Desktop? (y/n): "
    if /i "!install_docker!"=="y" (
        echo %CYN%Installing Docker Desktop...%RST%
        winget install -e --id Docker.DockerDesktop --accept-source-agreements --accept-package-agreements
        echo %YEL%[IMPORTANT] Docker requires a system restart.%RST%
        pause
        exit /b
    ) else (
        echo %RED%Cannot continue without Docker. Exiting.%RST%
        exit /b
    )
)

docker info >nul 2>&1
if !errorlevel! neq 0 (
    echo %YEL%[INFO] Docker Desktop is not running. Starting...%RST%
    start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"

    echo %CYN%Waiting for Docker engine to initialize...%RST%
    set /a attempts=0
    :docker_wait_loop
    timeout /t 2 /nobreak >nul
    docker info >nul 2>&1
    if !errorlevel! equ 0 goto docker_ready
    set /a attempts+=1
    if !attempts! geq 30 (
        echo %RED%[ERROR] Docker failed to start within 60 seconds. Please start it manually.%RST%
        pause
        exit /b
    )
    goto docker_wait_loop
)

:docker_ready
if not exist docker-compose.yml (
    echo %RED%[ERROR] docker-compose.yml not found in current directory!%RST%
    pause
    exit /b
)

echo.
echo %GRN%Starting %SVC% via Docker Compose...%RST%
echo %CYN%---------------------------------------------------%RST%
docker compose run --rm %SVC%
goto end

:: NATIVE WINDOWS EXECUTION

:local_mode
echo.
echo %CYN%[MODE] Switched to Native Windows Mode.%RST%

java -version >nul 2>&1
if !errorlevel! neq 0 (
    echo %RED%[MISSING] Java JDK not found.%RST%
    set /p install_java="Install Eclipse Temurin JDK 21? (y/n): "
    if /i "!install_java!"=="y" (
        echo %CYN%Installing JDK 21...%RST%
        winget install -e --id EclipseAdoptium.Temurin.21.JDK --accept-source-agreements --accept-package-agreements
        set RESTART_REQUIRED=1
    )
) else (
    echo %GRN%[OK] Java is installed.%RST%
)

call mvn -version >nul 2>&1
if !errorlevel! neq 0 (
    echo %RED%[MISSING] Maven not found.%RST%
    set /p install_mvn="Install Apache Maven? (y/n): "
    if /i "!install_mvn!"=="y" (
        echo %CYN%Installing Apache Maven...%RST%
        winget install -e --id Apache.Maven --accept-source-agreements --accept-package-agreements
        set RESTART_REQUIRED=1
    )
) else (
    echo %GRN%[OK] Maven is installed.%RST%
)

if !RESTART_REQUIRED! equ 1 (
    echo.
    echo %YEL%[ACTION REQUIRED] New dependencies were installed.%RST%
    echo %YEL%You must close and reopen this terminal to load the new PATH variables.%RST%
    pause
    exit /b
)

echo.
echo %CYN%Building project locally...%RST%
if exist jshell\pom.xml cd jshell
if not exist pom.xml (
    echo %RED%[ERROR] pom.xml not found. Script must be in project root.%RST%
    pause
    exit /b
)

call mvn clean package -DskipTests

echo.
echo %GRN%Starting J-Shell...%RST%
echo %CYN%---------------------------------------------------%RST%
java -jar target/j-shell-*.jar
goto end

:end
echo.
echo %GRN%Session finished.%RST%
pause