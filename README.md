<div align="center">

# J-Shell

**A lightweight Java shell that runs common filesystem, text, compression, network, and process commands from one prompt**

[![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=flat&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?style=flat&logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat)](LICENSE)
[![Tests](https://img.shields.io/badge/Tests-42%20passing-brightgreen?style=flat)]()
[![Docker](https://img.shields.io/badge/Docker-ready-2496ED?style=flat&logo=docker&logoColor=white)](jshell/README-DOCKER.md)

37 commands · `&&` chaining · Myers diff · No native dependencies

</div>

---

## Overview

J-Shell is a shell emulator that runs entirely on the JVM — no OS-level shell, no native binaries. It covers filesystem navigation, text processing, compression, networking, and process inspection with correct POSIX semantics where the JVM allows.

**What makes it non-trivial:**
- Sealed `Command` interface — adding a command without registering it is a compile error
- `ExecutionResult` record — every command returns both an updated context and a POSIX exit code
- `&&` chaining — `mkdir foo && cd foo && touch bar` works correctly, stops on first failure
- Myers O(ND) diff — correct LCS-based diff, not line-number alignment
- Quote-aware tokenizer — `echo "hello world" > file.txt` produces three tokens, not five
- Immutable session state — `cd` returns a new `ShellContext`, nothing mutates in place

---

## Start Here

From the repository root:

```powershell
.
un.bat
```

Or, if you are already inside [jshell](jshell), run:

```bash
mvn -q compile exec:java
```

If you want the packaged jar instead:

```bash
mvn clean package
java -jar target/j-shell-2.0.0.jar
```

```
Welcome to J-Shell — type 'help' or 'exit'.

/home/user/projects > mkdir -p src/main/java && cd src && touch README.md
/home/user/src > echo "hello world" > greeting.txt
/home/user/src > grep "hello" greeting.txt
hello world
/home/user/src > diff greeting.txt greeting.txt
Files are identical.
/home/user/src > exit
Goodbye!
```

> **Docker users** — see [README-DOCKER.md](jshell/README-docker.md) to run without installing Java or Maven locally.

---

## Requirements

| Tool  | Version |
|-------|---------|
| Java  | 17+     |
| Maven | 3.8+    |

---

## Commands

### Filesystem

| Command | Usage | Description |
|---------|-------|-------------|
| `ls` | `ls` | List files and directories with type and size |
| `pwd` | `pwd` | Print current working directory |
| `cd` | `cd [directory]` | Change directory — supports `~`, `..`, relative and absolute paths |
| `mkdir` | `mkdir [-p] <dir>` | Create directory; `-p` creates nested parents |
| `touch` | `touch <file>` | Create file or update modification time |
| `rm` | `rm [-r] <target>` | Remove file or directory; `-r` for recursive |
| `cp` | `cp [-r] <src> <dest>` | Copy file or directory; `-r` for recursive |
| `mv` | `mv <src> <dest>` | Move or rename |
| `cat` | `cat <file>` | Stream file contents — safe on large files |
| `find` | `find <pattern> [-r]` | Search filenames by substring; `-r` recurses |
| `du` | `du [-h] [path]` | Show disk usage; `-h` human-readable |

### Text Processing

| Command | Usage | Description |
|---------|-------|-------------|
| `echo` | `echo <text> [> file] [>> file]` | Print text; supports redirect and append |
| `grep` | `grep [-i] <pattern> <file>` | Full regex search; `-i` case-insensitive; exit 1 on no match |
| `wc` | `wc [-l\|-w\|-c] <file>` | Count lines, words, or characters |
| `diff` | `diff <file1> <file2>` | Myers O(ND) diff — correct on insertions and deletions |
| `sort` | `sort [-r] [-n] <file>` | Sort lines; `-r` reverse, `-n` numeric |
| `uniq` | `uniq [-c] <file>` | Remove adjacent duplicate lines (POSIX-correct); `-c` shows count |
| `head` | `head [-n count] <file>` | Print first N lines — stops reading early |
| `tail` | `tail [-n count] <file>` | Print last N lines — O(N) ring buffer |

### Compression

| Command | Usage | Description |
|---------|-------|-------------|
| `zip` | `zip [-r] <output.zip> <files...>` | Create zip archive; `-r` includes directories |
| `unzip` | `unzip <file.zip> [dest]` | Extract zip — zip slip path traversal blocked |
| `gzip` | `gzip <file>` | Compress file to `<file>.gz` |
| `gunzip` | `gunzip <file.gz>` | Decompress `.gz` file |

### Networking

| Command | Usage | Description |
|---------|-------|-------------|
| `ping` | `ping <host> [count]` | Ping a host (see known limitations) |
| `wget` | `wget <url> [filename]` | Download file with progress display |
| `curl` | `curl [-o <file>] <url>` | Fetch URL — print or save response |
| `ifconfig` | `ifconfig` | List network interfaces and addresses |

### System & Process

| Command | Usage | Description |
|---------|-------|-------------|
| `ps` | `ps [-v]` | JVM process stats; `-v` lists all threads |
| `exec` | `exec <command> [args...]` | Run an external system command |
| `env` | `env [variable]` | Show environment variables or look up one |
| `uname` | `uname` | OS, Java version, CPU count, memory |
| `whoami` | `whoami` | Current OS username |
| `date` | `date` | Current date and time (RFC-1123) |
| `history` | `history` | Numbered command history for this session |
| `clear` | `clear` | Clear the terminal screen |
| `help` | `help` | List all available commands with usage |
| `checksum` | `checksum [-md5\|-sha1\|-sha256] <file>` | Compute file hash (default SHA-256) |

---

## && Chaining

Commands can be chained with `&&`. Each stage runs only if the previous stage exited with code 0.

```bash
mkdir project && cd project && touch main.java && echo "ready" > status.txt
```

Quoted `&&` is not treated as a separator:

```bash
echo "hello && world" > file.txt   # writes: hello && world
```

---

## Architecture

```
App  (REPL loop + && dispatcher)
├── ShellContext        Session state — immutable directory via withDirectory()
├── Tokenizer           Quote-aware character state machine
├── CommandRegistry     String → Command lookup via Optional<Command>
├── ExecutionResult     record(ShellContext context, int exitCode)
├── Command (sealed)    ExecutionResult execute(ShellContext, String[])
│   ├── FileSystemCommands        ls pwd cd mkdir touch rm cp mv cat find du
│   ├── FileManipulationCommands  touch rm cat
│   ├── TextCommands              echo grep help
│   ├── AdvancedFileCommands      cp mv
│   ├── SystemCommands            history whoami date clear
│   ├── SearchCommands            find wc diff
│   ├── CompressionCommands       zip unzip gzip gunzip
│   ├── NetworkCommands           ping wget curl ifconfig
│   ├── ProcessCommands           ps exec env uname
│   └── UtilityCommands           sort uniq checksum du head tail
└── ByteFormatter       Shared byte size formatting
```

### Exit Code Semantics

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Failure or meaningful negative result (`grep` no-match, `diff` files-differ) |
| 2 | Misuse — bad arguments or missing operands |

---

## Design Highlights

**Sealed `Command` interface** — every permitted subtype is declared in the `permits` list. Adding a command without listing it is a compile error. Silent registration gaps are impossible.

**`ExecutionResult` record** — `execute()` returns both the updated `ShellContext` and a POSIX exit code. `&&` chaining inspects `result.succeeded()` to decide whether to continue the chain.

**Immutable session state** — `cd` calls `context.withDirectory()` which returns a new `ShellContext`. The directory field is `final`. Nothing mutates in place.

**Myers O(ND) diff** — the `DiffCommand` implements the standard LCS-based algorithm. Inserting a line in one file correctly marks only that insertion — not every subsequent line as changed.

**Streaming I/O** — `cat`, `grep`, `wc`, `head` all use `BufferedReader`. `tail` uses a ring buffer: O(N) memory for any file size where N is the requested line count.

**Zip slip prevention** — `unzip` compares canonical paths before writing any entry. The check includes a `File.separator` suffix to prevent prefix-match bypass.

---

## Usage Model

J-Shell reads one command per line, runs it inside the current session, and keeps the working directory between commands. The quickest workflow is:

1. Start the shell with `.
un.bat`.
2. Type commands like `pwd`, `ls`, `cd demo`, `echo hello > a.txt`, or `help`.
3. Use `exit` to leave the shell.

You can also chain commands with `&&`, for example `mkdir demo && cd demo && touch notes.txt`.

---

## Known Limitations

**`ping`** — `InetAddress.isReachable()` requires ICMP privilege on Linux. Without root it falls back to TCP/7, universally blocked. Workaround: `exec ping <host>`.

**`clear`** — ANSI escape codes. Works on Linux, macOS, Windows Terminal. Fails on legacy `cmd.exe`.

**`exec`** — no sandboxing. Runs as the current JVM user. Do not expose over a network.

**No pipelines** — `ls | grep txt | wc -l` is not supported. Requires interface changes to `execute()` to accept explicit `InputStream`/`PrintStream` parameters.

---

## Project Structure

```
jshell/
├── src/
│   ├── main/java/com/devops/
│   │   ├── App.java
│   │   ├── ExecutionResult.java
│   │   ├── ShellContext.java
│   │   ├── Command.java
│   │   ├── CommandRegistry.java
│   │   ├── Tokenizer.java
│   │   ├── ByteFormatter.java
│   │   ├── FileSystemCommands.java
│   │   ├── FileManipulationCommands.java
│   │   ├── TextCommands.java
│   │   ├── AdvancedFileCommands.java
│   │   ├── SystemCommands.java
│   │   ├── SearchCommands.java
│   │   ├── CompressionCommands.java
│   │   ├── NetworkCommands.java
│   │   ├── ProcessCommands.java
│   │   └── UtilityCommands.java
│   └── test/java/com/devops/
│       └── AppTest.java
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

---

## Contributing

1. Add a `static final class` implementing `Command` in the appropriate `*Commands.java` group file.
2. Add it to the `permits` list in `Command.java` — the compiler enforces this.
3. Register it in `App.registerCommands()`.
4. Implement `name()` and `usage()` — it appears in `help` automatically.
5. Return `ExecutionResult.ok(context)` on success, `ExecutionResult.fail(context)` on error, `ExecutionResult.misuse(context)` on bad arguments.
6. Add tests in `AppTest.java` using a `@TempDir`-isolated `ShellContext`.

---

<div align="center">

Java 21 · Sealed interfaces · Myers diff · 42 tests passing · Docker ready

</div>
