#!/usr/bin/env bash

# Exit immediately if a pipeline returns a non-zero status (safety net)
set -o pipefail

# ENABLE ANSI COLORS
GRN='\033[0;32m'
RED='\033[0;31m'
YEL='\033[1;33m'
CYN='\033[0;36m'
RST='\033[0m'

# SET SCRIPT DIRECTORY & INITIALIZE
# Safely navigate to the script's actual directory
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR" || exit 1

# Prevent Docker daemon from creating root-owned mount directories
mkdir -p workspace

show_menu() {
    clear
    echo -e "${CYN}===================================================${RST}"
    echo -e "   J-Shell Quickstart & Environment Setup"
    echo -e "${CYN}===================================================${RST}"
    echo ""
    echo -e "[${CYN}1${RST}] Run J-Shell (Docker - Standard Session)"
    echo -e "[${CYN}2${RST}] Run Tests (Docker - Maven Test Suite)"
    echo -e "[${CYN}3${RST}] Run Dev Mode (Docker - Live Rebuild from ./src)"
    echo -e "[${CYN}4${RST}] Run Locally (Native Host - Requires Java/Maven)"
    echo -e "[${CYN}q${RST}] Quit"
    echo ""
}

run_docker_mode() {
    local svc=$1
    echo ""
    echo -e "${CYN}[MODE] Containerized Execution: $svc${RST}"

    # Check for Docker
    if ! command -v docker &> /dev/null; then
        echo -e "${RED}[MISSING] Docker is not installed or not in PATH.${RST}"
        echo -e "${YEL}Please install Docker Desktop (macOS/Windows) or Docker Engine (Linux).${RST}"
        return
    fi

    # Check if Docker engine is running
    if ! docker info &> /dev/null; then
        echo -e "${RED}[ERROR] Docker daemon is not running.${RST}"
        echo -e "${YEL}Please start Docker Desktop or the docker service manually and try again.${RST}"
        return
    fi

    if [ ! -f "docker-compose.yml" ]; then
        echo -e "${RED}[ERROR] docker-compose.yml not found in current directory!${RST}"
        return
    fi

    echo ""
    echo -e "${GRN}Starting $svc via Docker Compose...${RST}"
    echo -e "${CYN}---------------------------------------------------${RST}"

    # Run the target container interactively
    docker compose run --rm "$svc"

    echo ""
    echo -e "${GRN}Session finished.${RST}"
    read -n 1 -s -r -p "Press any key to continue..."
    echo ""
}

run_local_mode() {
    echo ""
    echo -e "${CYN}[MODE] Switched to Native Host Mode.${RST}"

    # Check Java
    if ! command -v java &> /dev/null; then
        echo -e "${RED}[MISSING] Java JDK not found. Please install JDK 21.${RST}"
        return
    fi

    # Check Maven
    if ! command -v mvn &> /dev/null; then
        echo -e "${RED}[MISSING] Apache Maven not found. Please install Maven.${RST}"
        return
    fi

    echo ""
    echo -e "${CYN}Building project locally...${RST}"

    # Auto-navigate to project root if script is sitting one level above
    if [ -d "jshell" ] && [ -f "jshell/pom.xml" ]; then
        cd jshell || return
    fi

    if [ ! -f "pom.xml" ]; then
        echo -e "${RED}[ERROR] pom.xml not found. Cannot build Maven project.${RST}"
        # Navigate back if we changed dirs
        cd "$DIR" || return
        return
    fi

    mvn clean package -DskipTests

    echo ""
    echo -e "${GRN}Starting J-Shell...${RST}"
    echo -e "${CYN}---------------------------------------------------${RST}"

    # Execute the built JAR
    java -jar target/j-shell-*.jar

    # Return to original directory when done
    cd "$DIR" || return

    echo ""
    echo -e "${GRN}Session finished.${RST}"
    read -n 1 -s -r -p "Press any key to continue..."
    echo ""
}

# MAIN LOOP
while true; do
    show_menu
    read -r -p "Select an option (1-4, q to quit): " choice

    case "$choice" in
        1) run_docker_mode "jshell" ;;
        2) run_docker_mode "jshell-test" ;;
        3) run_docker_mode "jshell-dev" ;;
        4) run_local_mode ;;
        q|Q) echo -e "${GRN}Exiting.${RST}"; exit 0 ;;
        *) echo -e "${RED}[ERROR] Invalid choice.${RST}"; sleep 1.5 ;;
    esac
done