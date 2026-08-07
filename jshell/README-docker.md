# J-Shell — Docker Guide

Run J-Shell without installing Java or Maven locally. Three modes are available depending on what you need.

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) 24+
- [Docker Compose](https://docs.docker.com/compose/install/) v2+

Verify:

```bash
docker --version        # Docker version 24.x or higher
docker compose version  # Docker Compose version v2.x or higher
```

---

## Image Overview

The `Dockerfile` uses a two-stage build:

| Stage | Base image | Purpose |
|-------|-----------|---------|
| `build` | `maven:3.9-eclipse-temurin-21` | Compiles sources, runs tests, produces fat jar |
| `runtime` | `eclipse-temurin:21-jre-alpine` | Runs the jar — ~70 MB, no JDK, no Maven |

The runtime image runs as a non-root user (`jshell`) by default.

---

## Services

Three services are defined in `docker-compose.yml`:

| Service | Purpose | Exits when done? |
|---------|---------|-----------------|
| `jshell` | Interactive shell session | No — stays open |
| `jshell-test` | Runs `mvn test` and prints results | Yes |
| `jshell-dev` | Live dev — mounts source, builds and runs | No — stays open |

---

## Quickstart — Interactive Session

```bash
cd jshell

# Build the image (first time only)
docker compose build jshell

# Start an interactive session
docker compose run --rm jshell
```

```
Welcome to J-Shell — type 'help' or 'exit'.

/app/workspace > mkdir project && cd project && touch main.java
/app/workspace/project > echo "hello from docker" > notes.txt
/app/workspace/project > cat notes.txt
hello from docker
/app/workspace/project > exit
Goodbye!
```

Files written to `/app/workspace` inside the container are persisted in `./workspace` on your host.

---

## Running Tests

```bash
cd jshell

docker compose run --rm jshell-test
```

Expected output:

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.devops.AppTest
[INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Maven dependencies are cached in a named Docker volume (`maven-cache`). Subsequent runs skip the download phase.

---

## Development Mode

Mounts your local `src/` and `pom.xml` into the container read-only. Any local source change triggers a rebuild when you restart the service.

```bash
cd jshell

docker compose run --rm jshell-dev
```

The container runs `mvn clean package -DskipTests -q` on startup, then launches the shell. Edit source locally, restart the service to pick up changes.

```bash
# Rebuild after source changes
docker compose run --rm jshell-dev
```

---

## Persistent Workspace

The `./workspace` directory (relative to `jshell/`) is mounted into the container at `/app/workspace`. Files created there persist across container restarts.

```bash
# Create the directory if it doesn't exist yet
mkdir -p jshell/workspace

# Start session — files in /app/workspace are mirrored to ./workspace on your host
docker compose run --rm jshell
```

---

## Building Manually

```bash
cd jshell

# Build only the runtime image
docker build --target runtime -t jshell:latest .

# Run it directly (without Compose)
docker run --rm -it \
  -v "$(pwd)/workspace:/app/workspace" \
  jshell:latest
```

Build only the test stage (useful in CI):

```bash
docker build --target build -t jshell-build:latest .
docker run --rm jshell-build:latest mvn test
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `JAVA_OPTS` | `-Xmx512m -Xms128m -XX:+UseSerialGC` | JVM flags passed to the runtime |
| `MAVEN_OPTS` | `-Xmx512m` | Maven JVM flags (test service only) |

Override at runtime:

```bash
docker compose run --rm -e JAVA_OPTS="-Xmx1g" jshell
```

---

## Volumes

| Volume | Type | Mounted at | Purpose |
|--------|------|-----------|---------|
| `./workspace` | Bind mount | `/app/workspace` | Persistent file storage across sessions |
| `maven-cache` | Named volume | `/root/.m2` | Maven dependency cache — survives container removal |

Clear the Maven cache:

```bash
docker volume rm jshell_maven-cache
```

---

## Cleanup

```bash
# Remove containers
docker compose down

# Remove containers and named volumes (clears Maven cache)
docker compose down -v

# Remove built images
docker rmi jshell:latest jshell-test:latest jshell-dev:latest

# Full clean
docker compose down -v --rmi all
```

---

## CI Integration

Run tests in CI without any local Java setup:

```yaml
# .github/workflows/test.yml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Run tests
        run: |
          cd jshell
          docker compose run --rm jshell-test
```

---

## Known Limitations in Docker

**`ping`** — ICMP is blocked in most container runtimes without `--cap-add NET_ADMIN`. Use `exec ping <host>` to delegate to the Alpine system binary instead.

**`clear`** — ANSI escape codes work in Docker's TTY mode (`-it`). They have no effect when stdout is redirected.

**`exec`** — runs inside the container as the `jshell` user. Commands available depend on what is installed in the Alpine runtime image. The base image includes `sh`, `ls`, `cat`, and standard Alpine utilities.

**Interactive mode requires TTY** — always use `docker compose run` (not `docker compose up`) for interactive sessions. `docker compose up` does not allocate a TTY.
