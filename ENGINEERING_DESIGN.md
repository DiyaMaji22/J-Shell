# J-Shell Engineering Design Document

**Version:** 3.0
**Status:** Final
**Audience:** Senior Engineers / System Design Review

---

## 1. Overview

### Problem Statement

Implement a shell emulator running entirely on the JVM — no OS-level shell, no native binaries — exposing a POSIX-like interface for filesystem manipulation, text processing, compression, networking, and process inspection. The design must be type-safe, extensible without modifying the dispatch layer, correct under large inputs, and containerisable for local and CI use.

### Goals

- 37 shell commands with correct POSIX(portable os interface) semantics where the JVM permits
- `&&` chaining with proper short-circuit on failure
- POSIX exit code contract: 0 success, 1 failure/negative-result, 2 misuse
- Exhaustive command hierarchy enforced at compile time (sealed interface)
- No global mutable state — session state flows through the call graph
- Streaming I/O on all file-reading commands — no OOM on large inputs
- Zip slip path traversal blocked
- `mvn test` passes without configuration; Docker support for CI and local use

### Non-Goals

- Pipelines (`ls | grep | wc`) — deferred; requires interface signature change
- Process sandboxing for `exec`
- Concurrent or multiplexed sessions
- Full POSIX compliance (globbing, variable expansion, escape sequences)
- Windows `cmd.exe` compatibility beyond ANSI codes
- Persistent history across sessions

---

## 2. System Architecture

### REPL Lifecycle

```
stdin
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│  App.main()  — owns ShellContext + CommandRegistry           │
│                                                              │
│  read line ──► splitOnAnd() ──► stages[]                     │
│                                    │                         │
│                          for each stage (while succeeded):   │
│                                    │                         │
│                             Tokenizer.tokenize()             │
│                                    │                         │
│                          CommandRegistry.find()              │
│                                    │                         │
│                           Optional<Command>                  │
│                            │              │                  │
│                         present         empty                │
│                            │              │                  │
│                    cmd.execute(           stderr: not found  │
│                      context, args)       return fail        │
│                            │                                 │
│                    ExecutionResult                           │
│                      .context()  ──► rebind ShellContext     │
│                      .exitCode() ──► && short-circuit?       │
└──────────────────────────────────────────────────────────────┘
         │                    │
       stdout               stderr
```

### Component Responsibilities

| Component | Responsibility | Mutability |
|---|---|---|
| `App` | REPL loop, `&&` dispatch, command registration | Stateless |
| `ShellContext` | Session state (cwd, history) | Immutable directory; history append-only |
| `Tokenizer` | Quote-aware input → `String[]` | Stateless, pure |
| `CommandRegistry` | Name → `Command` via `Optional` | Write-once at startup, read-only thereafter |
| `Command` (sealed) | Execution contract | Stateless implementations |
| `ExecutionResult` | Return carrier: context + exit code | Immutable record |
| `ByteFormatter` | Byte size formatting | Stateless, pure |

---

## 3. Core Design Decisions

### 3.1 Sealed `Command` Interface

**Decision:** `Command` is `sealed` with an explicit `permits` list covering all 37 implementations.

**Problem solved:** In an unsealed design, a developer can implement `Command`, forget to add it to `permits`, and the omission is invisible until runtime. With a sealed interface, the compiler rejects any `Command` subtype not listed — registration gaps become compile errors.

**Tradeoff:** The `permits` list must be updated when adding a command. This is deliberate friction — it forces a developer to make an explicit declaration, which is precisely the property we want.

**Alternatives considered:**

| Option | Rejected reason |
|---|---|
| `switch` on command name | Violates Open/Closed; every addition touches dispatch |
| Reflection / `ServiceLoader` | Eliminates compile-time safety; scanning is fragile |
| Unsealed interface | Silent omission possible; no exhaustiveness guarantee |

---

### 3.2 `ExecutionResult` Record

**Decision:** `Command.execute()` returns `ExecutionResult(ShellContext context, int exitCode)` rather than `void`, `int`, or `ShellContext` alone.

**Problem solved:** Three separate problems converged on this design:
1. `void` — no exit code; `&&` chaining cannot determine whether to continue.
2. `int` alone — loses the updated context; `cd` cannot propagate the new directory.
3. `ShellContext` alone — loses exit code; `grep` no-match (exit 1) and `diff` files-differ (exit 1) are indistinguishable from success.

A record carrying both fields solves all three simultaneously. The factory methods (`ok`, `fail`, `misuse`, `of`) make intent explicit at call sites.

**Exit code semantics:**

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Failure or meaningful negative result (`grep` no match, `diff` files differ) |
| 2 | Misuse — bad argument count or format |

**Tradeoff:** Every command now carries context through the return path even when the context is unchanged. For 36 of 37 commands this returns the same instance — no allocation, just a record wrapping the existing reference.

---

### 3.3 `ShellContext` — Immutable Directory Propagation

**Decision:** `currentDirectory` is `final`. `cd` calls `context.withDirectory(canonical)` which returns a new `ShellContext` sharing the same history list. The REPL rebinds: `context = result.context()`.

**Problem solved:** The original design had `public static File currentDirectory` on `App` — shared mutable global state. This made parallel test execution impossible, introduced potential data races, and coupled every command to `App`.

**Canonical path invariant:** `ShellContext` always stores the result of `getCanonicalFile()`. This resolves `..` and symlinks at write time rather than read time, so all downstream path construction is free of traversal components.

**History sharing:** `withDirectory()` passes the existing `List<String>` reference into the new context rather than copying it. History is logically session-scoped, not directory-scoped — all commands in a session share the same history regardless of which directory is current.

**Tradeoff:** `addHistory()` is still a mutation — the history list is mutable internally, exposed as an unmodifiable view externally. Making history fully immutable (copy-on-write list) would require allocating a new list on every command, which is unnecessary for an interactive REPL.

---

### 3.4 `&&` Chaining in `App.dispatch()`

**Decision:** Input is split on `&&` (respecting quoted regions) before tokenisation. Each stage executes only if the previous stage returned `exitCode == 0`.

**Implementation:** `splitOnAnd()` uses the same two-flag quoting state machine as `Tokenizer`. This means `echo "hello && world"` produces one stage — the `&&` inside double quotes is not treated as a separator.

**Short-circuit semantics:** The loop breaks on the first non-zero exit code when the stage count is greater than one. Single-command input bypasses the short-circuit check entirely.

**Tradeoff:** `dispatch()` is `public static` to enable direct testing without going through stdin. This is a testing convenience that slightly breaks encapsulation — acceptable given the alternative is an integration-only test surface.

---

### 3.5 `&&` vs Pipelines

Chaining with `&&` is semantically simpler than pipelines because stages are sequential and independent — each reads from disk and writes to stdout. Pipelines (`|`) require connecting one command's stdout to the next command's stdin, which means:
- `execute()` must accept explicit `InputStream` / `PrintStream` parameters
- Stages must run on separate threads (blocking pipe + single thread = deadlock)
- All 37 existing commands need updating

This is the primary remaining architectural work to reach full shell behaviour.

---

### 3.6 Myers O(ND) Diff

**Decision:** `DiffCommand` implements the standard Myers O(ND) shortest-edit-script algorithm rather than line-number alignment.

**Problem solved:** Line-number alignment compares `a[i]` with `b[i]`. If a line is inserted at position 2 in file B, every subsequent line in A is compared against the wrong line in B and marked as changed. Myers finds the minimal edit script — only actual insertions and deletions appear in the output.

**Algorithm summary:**
- Forward pass: compute the furthest-reaching D-path for increasing edit distances D, recording a snapshot of the frontier array at each step.
- Backward pass: walk the trace in reverse to reconstruct which moves (insert/delete/snake) were taken.
- Output: lines prefixed `+` (only in b), `-` (only in a). Common lines are filtered from the output for conciseness.

**Complexity:** O(ND) time, O(N+M) space for the trace snapshots.

**Exit codes:** 0 = identical, 1 = files differ (matches POSIX `diff` convention).

---

## 4. Execution Model

### Single-Command Lifecycle

```
raw input string
    │
    ├── splitOnAnd() → single stage
    │
    ▼
Tokenizer.tokenize(stage)
    │   quote-aware split → String[]
    ▼
args[0] = command name
    │
    ▼
CommandRegistry.find(name) → Optional<Command>
    │
    ├── empty  → stderr "not found", ExecutionResult.fail(context)
    │
    └── present → cmd.execute(context, args)
                      │
                      ├── validate arg count/format → misuse(context) if bad
                      ├── validate file existence   → fail(context) if missing
                      ├── perform I/O
                      ├── write results to System.out
                      ├── write errors  to System.err
                      └── return ok / fail / of(context, 1) / misuse
```

### `&&` Chain Lifecycle

```
"mkdir foo && cd foo && touch bar"
    │
    ├── splitOnAnd() → ["mkdir foo", " cd foo", " touch bar"]
    │
    ▼
stage 1: "mkdir foo"
    └── execute → ok(context)  → context unchanged, continue
stage 2: " cd foo"
    └── execute → ok(newCtx)   → context = newCtx (new dir), continue
stage 3: " touch bar"
    └── execute → ok(newCtx)   → context = newCtx, loop ends
    │
    return final context
```

If stage 2 had returned `fail`, stage 3 would not execute.

### Current Limitations

- No inter-command pipes — stdout is always `System.out`, not connectable
- No background execution — all commands block the main thread
- No `SIGINT` handling — Ctrl+C terminates the JVM
- No command substitution (`$(...)`)
- No variable expansion (`$VAR`)
- History is in-process only — not persisted across sessions

---

## 5. Data & State Management

### `ShellContext`

```
ShellContext
  ├── currentDirectory : File   (final, canonical, never null)
  └── history          : List<String>  (internal ArrayList, exposed as unmodifiable)
```

**Canonical path invariant:** set at construction and at every `withDirectory()` call. Downstream code never calls `getCanonicalFile()` — it is guaranteed by the type.

**History contract:** `addHistory()` appends to the internal list. `history()` returns `Collections.unmodifiableList(history)` — a zero-allocation wrapper. External callers can iterate, get by index, and check size. They cannot add, remove, or clear.

### Thread Safety

`ShellContext` is single-owner by design. The REPL main thread creates it, passes it into commands by value at each call, and rebinds from the return value. No synchronisation is required. If concurrent execution were introduced, `ShellContext` would need to become a true immutable value type (all fields `final`, history as a persistent list), or synchronised access would be needed.

`CommandRegistry` is effectively immutable after `registerCommands()` completes. The internal `HashMap` is wrapped in `Collections.unmodifiableMap()` in `all()` but not in `find()` — which is safe because `find()` only reads.

---

## 6. Command Abstraction

### Interface

```java
public sealed interface Command permits ... {
    ExecutionResult execute(ShellContext context, String[] args);
    default String name()  { return ""; }
    default String usage() { return ""; }
}
```

`name()` and `usage()` are `default` — implementing them is optional. `HelpCommand` filters the registry for commands where `usage()` is non-empty, so commands that implement `usage()` appear in help automatically. This eliminates the possibility of the help listing drifting from the registered command set.

### Grouping

Commands are grouped into `final` namespace classes (`FileSystemCommands`, `TextCommands`, etc.) containing `static final` inner classes. The outer class has a `private` constructor and is never instantiated — it is a namespace. The grouping is by functional domain, matching both the help output categories and the natural conceptual groupings a developer would expect.

### Output Conventions

| Stream | Content |
|---|---|
| `System.out` | All command output — results, listings, file contents |
| `System.err` | All error messages — usage strings, file not found, I/O errors |

This separation means `cmd > file.txt` captures results without capturing errors. All commands write to these streams directly — there is no output buffering or capture at the command level.

### Composability Limitation

Direct writes to `System.out` mean commands cannot compose at the output level. A future pipeline implementation would require changing `execute()` to:

```java
ExecutionResult execute(ShellContext context, String[] args,
                        InputStream stdin, PrintStream stdout);
```

This is a breaking change to all 37 implementations.

---

## 7. Performance Characteristics

### Time and Space Complexity

| Command | Strategy | Memory | Time |
|---|---|---|---|
| `cat` | `BufferedReader` line-by-line | O(1) | O(n) |
| `grep` | `BufferedReader`, compiled pattern | O(1) | O(n) |
| `wc` | `BufferedReader`, accumulate counters | O(1) | O(n) |
| `head` | `BufferedReader`, early stop at N | O(N) | O(N) |
| `tail` | `BufferedReader` + ring buffer | O(N) requested lines | O(n) |
| `sort` | `readAllLines()` + `Collections.sort` | O(n) full file | O(n log n) |
| `diff` | `readAllLines()` × 2 + trace snapshots | O(n + m + ND) | O(ND) |
| `uniq` | `BufferedReader`, two variables | O(1) | O(n) |
| `checksum` | 8 KB byte buffer, streaming | O(1) | O(n) |
| `zip`/`gzip` | 8 KB I/O buffer | O(1) | O(n) |

**`sort` and `diff` are the only commands that load a full file into memory.** `sort` requires random access to all elements; there is no streaming sort for arbitrary comparators. `diff` requires both files in memory to build the edit graph.

### Pattern Compilation

`GrepCommand` calls `Pattern.compile()` once before the line loop, not inside it. Compiling inside the loop is O(pattern_length) per line — an unnecessary cost for a fixed pattern.

### Buffer Sizes

All byte-level I/O (zip, gzip, checksum) uses 8 KB buffers. The original codebase used 1 KB — below the typical OS page size and disk block size, causing unnecessary system call overhead. 8 KB is a reasonable default for sequential I/O without over-allocating.

---

## 8. Error Handling Strategy

### Principles

1. **Validate before executing.** Argument count and format are checked before any file is opened or network connection attempted.
2. **All errors to `System.err`.** `System.out` is for command output only.
3. **Nothing is swallowed silently.** Every `catch` block either returns a non-zero result or re-throws. No empty `catch` blocks exist.
4. **Interrupt status is restored.** `ProcessCommands.ExecCommand` restores `Thread.currentThread().interrupt()` after catching `InterruptedException`.

### Validation Order

```
1. argument count         → misuse(context) + usage printed
2. numeric argument parse → fail(context)   + specific message
3. file/directory exists  → fail(context)   + path printed
4. I/O operation          → fail(context)   + exception message
```

This ordering ensures the most actionable error is reported first. A wrong argument count is diagnosed before attempting to open a file that may not exist.

### Exit Code Overloading

Exit code 1 covers both hard errors (file not found, I/O failure) and meaningful negative results (`grep` no match, `diff` files differ). A richer convention (2 = error, 1 = negative result) would require callers to distinguish them — the current REPL treats all non-zero exits uniformly for `&&` short-circuit purposes. The distinction is available via `ExecutionResult.exitCode()` if a future scripting mode needs it.

---

## 9. Security Considerations

### Zip Slip — Path Traversal in `unzip`

**Threat:** A zip entry named `../../etc/cron.d/evil` resolves outside the extraction directory when naively combined with the destination path.

**Mitigation:**
```java
String destCanonical = destDir.getCanonicalPath();
File target = new File(destDir, entry.getName());
if (!target.getCanonicalPath().startsWith(destCanonical + File.separator)) {
    // block
}
```

`getCanonicalPath()` resolves all `..` and symlink components before comparison. The `+ File.separator` suffix prevents the prefix-collision bypass where `/tmp/safe` would match `/tmp/safetyvalve/evil`.

### External Command Execution (`exec`)

`ProcessBuilder` is used instead of `Runtime.exec(String)`. The difference: `ProcessBuilder` accepts a pre-tokenised `String[]`, so the OS launches the process directly without shell interpretation. `exec rm -rf /foo` passes three arguments to `rm`, not a shell string that could be reinterpreted.

**Residual risk:** No allowlist, no capability restriction, no resource limits. `exec` runs as the JVM user. This is acceptable for a local developer tool and must not be exposed over a network interface.

### Canonical Paths

`cd` stores `getCanonicalFile()`, not `getAbsoluteFile()`. This prevents symlink confusion where the stored path diverges from the real filesystem location.

---

## 10. Extensibility & Scalability

### Adding a Command — Four Steps

1. Create a `static final class` implementing `Command` in the appropriate `*Commands.java` file.
2. Add it to the `permits` list in `Command.java` — required by the compiler.
3. Register it in `App.registerCommands()`.
4. Implement `name()`, `usage()`, and `execute()` — appears in `help` automatically.

No other files change.

### Plugin System

The existing `Command` interface is compatible with Java's `ServiceLoader` SPI without modification:

```
META-INF/services/com.devops.Command   (in external JAR)
```

`App` would call `ServiceLoader.load(Command.class)` and register each entry. The sealed interface would need to be relaxed (`non-sealed`) for external implementations — a deliberate trade-off between extensibility and compile-time exhaustiveness.

### Pipeline Support

Requires three coordinated changes:
1. `execute()` signature: add `InputStream stdin, PrintStream stdout` parameters.
2. Execution model: pipeline stages must run on separate threads — a blocking pipe on a single thread deadlocks.
3. Tokenizer: detect `|` as a pipeline separator (outside quoted regions).

The `splitOnAnd()` implementation in `App` provides a template for the tokenizer change.

---

## 11. Testing Strategy

### Approach

42 unit tests using JUnit 5, each test creating an isolated `ShellContext` pointing at a `@TempDir`. No shared filesystem state between tests.

### Coverage

| Category | Tests | Key assertions |
|---|---|---|
| `ExecutionResult` | 4 | Factory methods, `succeeded()`, exit codes |
| Tokenizer | 4 | Double quotes, single quotes, mixed, unquoted |
| `ShellContext` | 3 | `withDirectory()` returns new instance, original unchanged, history unmodifiable |
| Filesystem | 8 | `cd` exit codes + context propagation, `mkdir -p`, `rm` recursive, `rm` directory guard, `rm` nonexistent |
| `cp` / `mv` | 4 | File copy with content check, recursive directory copy, missing source fail, rename |
| `echo` | 2 | Redirect (`>`), append (`>>`) |
| `grep` | 4 | Regex match, `-i` flag, exit 1 on no-match, invalid regex |
| Streaming | 4 | `head` correct output, `tail` correct output, ring buffer boundary, invalid count |
| Large file | 1 | 100k lines via `cat` without OOM |
| `uniq` | 2 | Adjacent-only deduplication, `-c` run counts |
| `sort` | 3 | Alphabetical, numeric `-n`, reverse `-r` — all with output assertions |
| `diff` | 4 | Identical (exit 0), differs (exit 1), insertion without offset, deletion without offset |
| Checksum | 1 | MD5 known-value (`5d41402...`) |
| Security | 1 | Zip slip path traversal blocked |
| `&&` chaining | 3 | All-succeed runs all, first-fail short-circuits, quoted `&&` not split |
| Integration | 5 | Full REPL loop: echo+cat, mkdir+cd, `&&` end-to-end, unknown command error, quoted args |

### Gaps

| Gap | Impact | Mitigation |
|---|---|---|
| No compression round-trip test | `zip` then `unzip` correctness untested | Add fixture-based test |
| No network command tests | `wget`, `curl`, `ping` logic untested | Mock `HttpURLConnection`; test with local server |
| No `exec` test | Subprocess execution untested | Use `exec echo hello` as a safe smoke test |
| `wc` output format untested | Column alignment could regress | Add output assertion test |

---

## 12. Known Limitations

| Limitation | Root Cause | Workaround |
|---|---|---|
| `ping` unreliable without root | `InetAddress.isReachable()` falls back to TCP/7 on Linux without ICMP privilege | `exec ping <host>` delegates to OS binary |
| `clear` fails on legacy Windows | ANSI codes not interpreted by `cmd.exe` | Use Windows Terminal |
| `exec` has no sandboxing | `ProcessBuilder` inherits JVM user permissions | Document as local-only; do not expose over network |
| No signal handling | JVM does not expose per-command `SIGINT` | Not addressable without JNI or shutdown hook |
| `sort` and `diff` load full file | Sorting requires full data; Myers needs both files for edit graph | Acceptable for interactive use; document size limits |
| No pipelines | `execute()` writes directly to `System.out` | Breaking interface change required |

---

## 13. CI / CD

### GitHub Actions

Two jobs run on every push and pull request to `main`/`master`:

**`test` job:**
- Sets up Java 21 (Temurin) with Maven cache
- Runs `mvn clean verify -B` in `jshell/`
- Uploads Surefire XML reports as an artifact (7-day retention)

**`docker` job (runs after `test` passes):**
- Builds the `runtime` stage of the multi-stage Dockerfile
- Uses GitHub Actions cache for Docker layer caching
- Runs `docker compose run --rm jshell-test` to verify tests pass inside the container

### Docker Services

| Service | Use case | Exit behaviour |
|---|---|---|
| `jshell` | Interactive session, `./workspace` bind-mounted | Stays open |
| `jshell-test` | CI / local `mvn test` | Exits with Maven return code |
| `jshell-dev` | Live development, source bind-mounted | Stays open |

---

## 14. Future Improvements

### Pipeline Support (High impact, breaking)

Change `execute()` to `ExecutionResult execute(ShellContext, String[], InputStream, PrintStream)`. Add `|` detection to `splitOnAnd()`. Run pipeline stages on a thread pool with `PipedInputStream`/`PipedOutputStream` connections. All 37 existing commands require updating.

### Persistent History

Write history to `~/.jshell_history` on exit, load on startup. Requires `ShellContext` to accept an optional pre-populated history list at construction, and a shutdown hook in `App`.

### `&&` / `||` / `;` Full Support

`||` (run if previous failed) and `;` (always run) are natural extensions. `splitOnAnd()` could be generalised to a stage parser that returns `List<Stage>` where each stage carries its separator type.

### Integration Test Coverage for Network and Compression

Mock `HttpURLConnection` to test `wget`/`curl` without network access. Add zip-then-unzip round-trip tests for `CompressionCommands`.