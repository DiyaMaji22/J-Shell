# J-Shell Code Guide

A walkthrough of the codebase for developers who want to understand, modify, or extend it. Sections follow the execution path of a single command — from keystroke to result — then cover each subsystem in depth.

---

## Table of Contents

1. How to navigate this project
2. Entry point — `App.java`
3. `&&` chaining — `splitOnAnd()` and `dispatch()`
4. Session state — `ShellContext.java`
5. Return contract — `ExecutionResult.java`
6. Input parsing — `Tokenizer.java`
7. Command interface and sealed hierarchy — `Command.java`
8. Registry and help — `CommandRegistry.java`, `HelpCommand`
9. Key command implementations
10. Shared utilities — `ByteFormatter.java`
11. Error handling patterns
12. Java 21 features in use
13. Testing patterns

---

## 1. How to Navigate This Project

Every execution path starts in `App.java` and ends in one of the `*Commands.java` files. The chain is always:

```
App → splitOnAnd → Tokenizer → CommandRegistry → Command → ExecutionResult
```

If you are adding a command, you touch: the appropriate `*Commands.java`, `Command.java` (`permits` list), and `App.registerCommands()`. Nothing else.

If you are changing how input is parsed, you touch `Tokenizer.java` and possibly `splitOnAnd()` in `App.java`.

If you are changing what the REPL does with a command's result, you touch `App.dispatch()`.

---

## 2. Entry Point — `App.java`

```java
public static void main(String[] args) {
    ShellContext context = new ShellContext(new File(System.getProperty("user.dir")));
    var registry = new CommandRegistry();
    registerCommands(registry);

    try (var scanner = new Scanner(System.in)) {
        while (true) {
            System.out.print(context.currentDirectory().getAbsolutePath() + " > ");

            if (!scanner.hasNextLine()) break;

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            context.addHistory(input);

            if (input.equals("exit")) { System.out.println("Goodbye!"); break; }

            context = dispatch(input, context, registry);
        }
    }
}
```

**Line by line:**

`new ShellContext(new File(System.getProperty("user.dir")))` — the JVM's working directory at launch becomes the shell's starting directory. This respects the directory you were in when you ran `java -jar`.

`scanner.hasNextLine()` before `nextLine()` — guards against EOF. If stdin is closed (e.g. piped input ends, or the integration test's input stream is exhausted), `hasNextLine()` returns false and the loop exits cleanly. Without it, `nextLine()` throws `NoSuchElementException`.

`context.addHistory(input)` — history is recorded before dispatch, so a command is recorded even if it fails. This matches bash behaviour.

`exit` is handled directly in the loop rather than as a `Command`. It needs to break the loop. If it were a `Command`, it would need to signal the loop externally — either via a mutable flag, a special exception, or a sentinel return value. Keeping it in the loop is simpler and honest.

`context = dispatch(...)` — the REPL rebinds `context` on every iteration. For 36 of 37 commands, `dispatch` returns the same `ShellContext` instance. For `cd`, it returns a new instance with the updated directory. This is the only place in the codebase where `context` is reassigned.

---

## 3. `&&` Chaining — `splitOnAnd()` and `dispatch()`

### `splitOnAnd()`

```java
private static List<String> splitOnAnd(String input) {
    List<String> stages = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inDouble = false;
    boolean inSingle = false;

    for (int i = 0; i < input.length(); i++) {
        char c = input.charAt(i);

        if      (c == '"'  && !inSingle) inDouble = !inDouble;
        else if (c == '\'' && !inDouble) inSingle = !inSingle;
        else if (c == '&' && !inDouble && !inSingle
                 && i + 1 < input.length() && input.charAt(i + 1) == '&') {
            stages.add(current.toString());
            current.setLength(0);
            i++; // consume the second '&'
            continue;
        }
        current.append(c);
    }
    if (!current.isEmpty()) stages.add(current.toString());
    return stages;
}
```

This is the same two-flag state machine as `Tokenizer.tokenize()`, applied to a different splitting problem. The key difference: it looks ahead one character (`input.charAt(i + 1) == '&'`) to detect the `&&` pair, then skips the second `&` with `i++`.

Input `echo "hello && world" && pwd` produces two stages:
- `echo "hello && world"` — the `&&` inside double quotes is not a separator
- ` pwd`

### `dispatch()`

```java
public static ShellContext dispatch(String input, ShellContext context, CommandRegistry registry) {
    List<String> stages = splitOnAnd(input);

    for (String stage : stages) {
        String[] parts = Tokenizer.tokenize(stage.trim());
        if (parts.length == 0) continue;

        String commandName = parts[0];
        ShellContext current = context;  // effectively final for lambda capture

        ExecutionResult result = registry.find(commandName)
            .map(cmd -> {
                try { return cmd.execute(current, parts); }
                catch (Exception e) {
                    System.err.println(commandName + ": unexpected error: " + e.getMessage());
                    return ExecutionResult.fail(current);
                }
            })
            .orElseGet(() -> {
                System.err.println("j-shell: command not found: " + commandName);
                return ExecutionResult.fail(current);
            });

        context = result.context();  // rebind — may be new instance (cd) or same

        if (!result.succeeded() && stages.size() > 1) break;  // && short-circuit
    }
    return context;
}
```

**Why `ShellContext current = context`:** Java lambdas can only capture variables that are effectively final. `context` is reassigned in the loop (`context = result.context()`), so it is not effectively final. `current` captures the value of `context` at the start of this iteration — it is never reassigned, so it qualifies. This is the standard workaround for loop-variable lambda capture.

**`&&` short-circuit condition:** `!result.succeeded() && stages.size() > 1`. The `stages.size() > 1` guard means single-command input never triggers the break check — a micro-optimisation that also prevents accidental early exit if a single command returns non-zero.

**`map` + `orElseGet`:** `registry.find()` returns `Optional<Command>`. `map` transforms the present case (execute the command). `orElseGet` supplies the absent case (print error). Both paths return an `ExecutionResult`, making the overall expression type-consistent.

---

## 4. Session State — `ShellContext.java`

```java
public final class ShellContext {
    private final File currentDirectory;   // always canonical
    private final List<String> history;    // internal ArrayList

    public ShellContext withDirectory(File canonical) {
        return new ShellContext(canonical, history);  // shares history list
    }

    public List<String> history() {
        return Collections.unmodifiableList(history);  // O(1) wrapper
    }
}
```

**Canonical path invariant:** The private constructor is called by both the public constructor (via `canonicalize()`) and `withDirectory()`. Both paths guarantee `currentDirectory` is a fully resolved absolute path — no `..`, no symlinks, no relative components. Every command that builds a child path (`new File(context.currentDirectory(), name)`) inherits this guarantee automatically.

**`withDirectory()` shares the history list:** Both the old and new `ShellContext` hold a reference to the same `ArrayList`. This is intentional — history is session-scoped, not directory-scoped. When `cd` produces a new context, the new context sees all previously recorded commands. There is no copy.

**Unmodifiable view:** `Collections.unmodifiableList(history)` is a wrapper that delegates reads to the underlying list and throws `UnsupportedOperationException` on any write. It is O(1) — no copy is made. The internal list can still be mutated via `addHistory()`, but external callers cannot.

**Why not a Java record:** Records make all fields public and `final`. `history` needs to be mutable internally (append-only). A record would force either exposing the mutation or using a copied immutable list on every `addHistory()` call. The current design is more efficient for an interactive REPL.

---

## 5. Return Contract — `ExecutionResult.java`

```java
public record ExecutionResult(ShellContext context, int exitCode) {

    public static ExecutionResult ok(ShellContext context)      { return new ExecutionResult(context, 0); }
    public static ExecutionResult fail(ShellContext context)    { return new ExecutionResult(context, 1); }
    public static ExecutionResult misuse(ShellContext context)  { return new ExecutionResult(context, 2); }
    public static ExecutionResult of(ShellContext context, int code) { return new ExecutionResult(context, code); }

    public boolean succeeded() { return exitCode == 0; }
}
```

This is a Java record — a transparent, immutable data carrier. The compiler generates the constructor, accessors (`context()`, `exitCode()`), `equals()`, `hashCode()`, and `toString()` automatically.

**Why factory methods instead of `new ExecutionResult(...)`:** The factories make the exit code semantic explicit at the call site. `ExecutionResult.ok(context)` reads as intent; `new ExecutionResult(context, 0)` is just a magic number. The `of()` factory exists for the two cases where exit code 1 means something other than error — `grep` no-match and `diff` files-differ. These use `ExecutionResult.of(context, 1)` to signal that the code is intentional and POSIX-specified, not a default.

**`succeeded()`:** The `&&` chain calls `result.succeeded()` rather than `result.exitCode() == 0`. This keeps the short-circuit logic readable and makes it easy to change the success threshold if needed without hunting for all `== 0` comparisons.

---

## 6. Input Parsing — `Tokenizer.java`

```java
public static String[] tokenize(String input) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inDouble = false;
    boolean inSingle = false;

    for (int i = 0; i < input.length(); i++) {
        char c = input.charAt(i);

        if      (c == '"'  && !inSingle) inDouble = !inDouble;
        else if (c == '\'' && !inDouble) inSingle = !inSingle;
        else if (c == ' '  && !inDouble && !inSingle) {
            if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        } else {
            current.append(c);
        }
    }

    if (!current.isEmpty()) tokens.add(current.toString());
    return tokens.toArray(String[]::new);
}
```

**State machine trace for `echo "hello world" > out.txt`:**

| Char | inDouble | inSingle | current | tokens |
|------|----------|----------|---------|--------|
| `e`…`o` | false | false | `echo` | `[]` |
| ` ` | false | false | `` | `["echo"]` |
| `"` | **true** | false | `` | `["echo"]` |
| `h`…`d` | true | false | `hello world` | `["echo"]` |
| `"` | **false** | false | `` | `["echo"]` |
| ` ` | false | false | `` | `["echo", "hello world"]` |
| `>` | false | false | `>` | `["echo", "hello world"]` |
| ` ` | false | false | `` | `["echo", "hello world", ">"]` |
| `o`…`t` | false | false | `out.txt` | `["echo", "hello world", ">"]` |
| EOF | — | — | — | `["echo", "hello world", ">", "out.txt"]` |

**`current.setLength(0)`** resets the `StringBuilder` in-place rather than creating `new StringBuilder()` on every space. For long inputs with many tokens this avoids repeated small allocations in the hot path.

**`tokens.toArray(String[]::new)`** — the `IntFunction<String[]>` overload of `toArray` introduced in Java 11. The method reference `String[]::new` is equivalent to `n -> new String[n]`. It is more idiomatic than `tokens.toArray(new String[0])` and avoids the subtle pessimism of pre-sizing the array.

---

## 7. Command Interface and Sealed Hierarchy — `Command.java`

```java
public sealed interface Command
    permits FileSystemCommands.ListCommand,
            FileSystemCommands.CdCommand,
            // ... all 37 ...
            UtilityCommands.TailCommand {

    ExecutionResult execute(ShellContext context, String[] args);
    default String name()  { return ""; }
    default String usage() { return ""; }
}
```

**Sealed interface mechanics:** The `sealed` keyword restricts which classes can implement the interface to those listed in `permits`. Any class not listed that attempts to implement `Command` will not compile. This makes the command hierarchy exhaustive — every possible `Command` is known at compile time.

**Practical consequence:** When you add a command, the compiler tells you to add it to `permits`. You cannot forget. In an unsealed design, a command class that is never registered in `permits` or in `registerCommands()` simply does nothing — no compile error, no runtime error, just silence.

**`default` methods:** `name()` and `usage()` return empty strings by default. Commands that implement them appear in the help listing; commands that do not are still valid `Command` implementations. The default prevents boilerplate on simple commands that do not need help entries.

**Static inner class grouping:**

```java
public final class FileSystemCommands {
    private FileSystemCommands() {}   // not instantiable — namespace only

    public static final class ListCommand implements Command { ... }
    public static final class CdCommand   implements Command { ... }
}
```

`static` — the inner class has no implicit reference to the outer class instance. Since the outer class is never instantiated (private constructor), a non-static inner class would carry a dangling reference. `static` is both correct and clearer.

`final` on inner classes — these are concrete leaf implementations, not designed for extension. `final` documents intent and enables JVM inlining optimisations.

`final` on outer class — the namespace class is not designed to be subclassed either.

---

## 8. Registry and Help — `CommandRegistry.java` and `HelpCommand`

### Registry

```java
public final class CommandRegistry {
    private final Map<String, Command> commands = new HashMap<>();

    public Optional<Command> find(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    public Map<String, Command> all() {
        return Collections.unmodifiableMap(commands);
    }
}
```

`Optional.ofNullable(commands.get(name))` — `HashMap.get()` returns `null` for missing keys. `Optional.ofNullable` converts null to `Optional.empty()` and a non-null value to `Optional.of(value)`. The caller receives an `Optional<Command>` and is forced to handle the empty case — the type system prevents silently ignoring a missing command.

`all()` returns an unmodifiable map view — `HelpCommand` reads it to generate output; it cannot accidentally modify the registry during execution.

### HelpCommand

```java
registry.all().entrySet().stream()
    .filter(e -> !e.getValue().usage().isEmpty())
    .sorted(Map.Entry.comparingByKey())
    .forEach(e -> System.out.printf("  %-12s %s%n", e.getKey(), e.getValue().usage()));
```

Help is generated from the registry at runtime, not from a hardcoded string. This means:
- A newly registered command with a `usage()` implementation appears in help automatically.
- A removed command disappears from help automatically.
- The alphabetical sort (`comparingByKey()`) means new commands slot in correctly with no manual ordering.

`"%-12s"` — left-aligns the command name in a 12-character field. All usage strings are then aligned in a column regardless of name length.

---

## 9. Key Command Implementations

### `CdCommand` — canonical paths and immutable context update

```java
return ExecutionResult.ok(context.withDirectory(target.getCanonicalFile()));
```

`getCanonicalFile()` resolves all `..` and symlink components. `cd /foo/../bar` stores `/bar`, not `/foo/../bar`. Without canonicalisation, subsequent `new File(context.currentDirectory(), name)` calls would produce paths with unresolved traversal components.

`withDirectory()` returns a new `ShellContext` — `cd` is the only command that produces a different context instance. The REPL rebinds `context = result.context()` on every iteration, so the updated directory propagates forward.

```java
private File resolveTarget(File current, String path) {
    return switch (path) {
        case "~"  -> new File(System.getProperty("user.home"));
        case ".." -> { File parent = current.getParentFile(); yield parent != null ? parent : current; }
        default   -> { File f = new File(path); yield f.isAbsolute() ? f : new File(current, path); }
    };
}
```

This switch expression covers home directory (`~`), parent (`..`), absolute paths, and relative paths in four arms with no fall-through.

### `CatCommand` — streaming large files

```java
try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
    String line;
    while ((line = reader.readLine()) != null) {
        System.out.println(line);
    }
}
```

`Files.readAllLines()` loads every line into a `List<String>` in heap memory. On a 4 GB log file this causes `OutOfMemoryError`. `BufferedReader.readLine()` reads from an 8 KB internal buffer — the previous chunk is eligible for garbage collection as you advance. Memory stays constant regardless of file size.

### `HeadCommand` — early stop

```java
while (count < lines && (line = reader.readLine()) != null) {
    System.out.println(line);
    count++;
}
```

The loop condition checks `count < lines` before calling `readLine()`. When N lines are printed, the condition is false before `readLine()` is called again. The try-with-resources block closes the reader — the OS never reads beyond the Nth line. On a 10 GB file, `head -n 10` reads approximately 10 line-lengths of data.

### `TailCommand` — ring buffer

```java
final int capacity = lines;
var ring = new String[capacity];
int pos = 0, total = 0;

while ((line = reader.readLine()) != null) {
    ring[pos % capacity] = line;   // wrap around, overwrite oldest
    pos++;
    total++;
}

int count = Math.min(total, capacity);
int start = total > capacity ? pos % capacity : 0;
for (int i = 0; i < count; i++) {
    System.out.println(ring[(start + i) % capacity]);
}
```

A ring buffer is a fixed-size array used as a circular queue. `pos % capacity` maps a monotonically increasing counter to an index within `[0, capacity)`. When `pos` exceeds `capacity`, it wraps and overwrites the oldest slot.

**Example with capacity=3, input lines 1–5:**

| Line | pos | ring after write |
|------|-----|-----------------|
| "1" | 0 | `["1", null, null]` |
| "2" | 1 | `["1", "2", null]` |
| "3" | 2 | `["1", "2", "3"]` |
| "4" | 3 | `["4", "2", "3"]` ← overwrites slot 0 |
| "5" | 4 | `["4", "5", "3"]` ← overwrites slot 1 |

After reading: `total=5`, `pos=5`, `start = 5 % 3 = 2`. Print from slot 2: `"3"`, `"4"`, `"5"` — the last 3 lines in correct order.

Memory: exactly `capacity` string references regardless of file size.

### `UniqCommand` — adjacent-only deduplication

```java
String prev = null;
int run = 0;
String line;
while ((line = reader.readLine()) != null) {
    if (line.equals(prev)) {
        run++;
    } else {
        if (prev != null) printUniq(prev, run, showCount);
        prev = line;
        run = 1;
    }
}
if (prev != null) printUniq(prev, run, showCount);
```

Real `uniq` collapses only adjacent duplicates. Input `a a b a` → output `a b a`. The previous implementation used a `LinkedHashMap` which deduplicated globally, producing `a b` — wrong.

This implementation tracks the current line and how many times it has appeared consecutively. When the line changes, the completed run is printed. The trailing `if` after the loop handles the final run that was not followed by a different line.

### `GrepCommand` — compiled regex, POSIX exit codes

```java
Pattern pattern = Pattern.compile(patternStr);  // compiled once, before the loop
// ...
while ((line = reader.readLine()) != null) {
    if (pattern.matcher(line).find()) {
        matchCount++;
    }
}
return matchCount > 0
    ? ExecutionResult.ok(context)
    : ExecutionResult.of(context, 1);
```

`Pattern.compile()` parses the regex and builds a finite automaton. Calling it inside the line loop re-parses on every line — O(pattern_length × line_count) instead of O(pattern_length + line_count).

Exit code 1 on no match is POSIX-specified — it is not an error. `ExecutionResult.of(context, 1)` is used rather than `ExecutionResult.fail(context)` to signal that this is a deliberate, documented exit code, not a generic failure.

### `DiffCommand` — Myers O(ND) algorithm

The algorithm runs in two phases:

**Forward pass — find the edit graph frontier:**

```java
for (int d = 0; d <= max; d++) {
    trace.add(v.clone());
    for (int k = -d; k <= d; k += 2) {
        int idx = k + max;
        int x;
        if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
            x = v[idx + 1]; // move down = insertion
        } else {
            x = v[idx - 1] + 1; // move right = deletion
        }
        int y = x - k;
        // extend snake: advance along matching lines without cost
        while (x < n && y < m && a.get(x).equals(b.get(y))) { x++; y++; }
        v[idx] = x;
        if (x >= n && y >= m) break outer;  // reached bottom-right corner
    }
}
```

`v[k]` stores the furthest x-position reachable on diagonal k. A diagonal represents `x - y = k`. Moving right (deletion) increments x. Moving down (insertion) increments y. Moving diagonally (match/snake) increments both without costing an edit.

`v.clone()` at the start of each `d` iteration saves a snapshot of the frontier. These snapshots are replayed in the backtrack phase.

**Backtrack — reconstruct the edit script:**

```java
for (int d = trace.size() - 1; d >= 0; d--) {
    int[] v = trace.get(d);
    int k = x - y;
    // determine whether this move was an insertion or deletion
    int prevK = (k == -d || (k != d && v[k-1+max] < v[k+1+max])) ? k + 1 : k - 1;
    int prevX = v[prevK + max];
    int prevY = prevX - prevK;
    // walk back along the snake (matching lines — no edit cost)
    while (x > prevX && y > prevY) { script.add(0, "  " + a.get(x-1)); x--; y--; }
    // record the single edit at this step
    if (x == prevX) { script.add(0, "+ " + b.get(y-1)); y--; }   // insertion
    else            { script.add(0, "- " + a.get(x-1)); x--; }   // deletion
}
```

Walking backwards through the trace determines which move was made at each edit distance level. Common lines (snakes) are collected but then filtered from the final output for conciseness.

**Why this matters:** The previous line-number alignment compared `a[i]` with `b[i]`. If line 2 is inserted in b, every subsequent line is compared against the wrong counterpart and marked as changed. Myers finds only actual changes.

### `UnzipCommand` — zip slip prevention

```java
String destCanonical = destDir.getCanonicalPath();
File target = new File(destDir, entry.getName());

if (!target.getCanonicalPath().startsWith(destCanonical + File.separator)
        && !target.getCanonicalPath().equals(destCanonical)) {
    System.err.println("unzip: blocked unsafe entry: " + entry.getName());
    zis.closeEntry();
    continue;
}
```

A zip entry named `../../etc/evil` combined with destination `/tmp/out` resolves to `/etc/evil` after `getCanonicalPath()`. The prefix check then fails — `/etc/evil` does not start with `/tmp/out/` — and the entry is skipped.

`+ File.separator` is critical: without it, destination `/tmp/safe` would match the canonical path `/tmp/safetyvalve/evil` because the string `/tmp/safetyvalve/evil` starts with `/tmp/safe`. Appending `/` (or `\` on Windows) forces a complete path component boundary.

---

## 10. Shared Utilities — `ByteFormatter.java`

```java
public final class ByteFormatter {
    private ByteFormatter() {}   // utility class — not instantiable

    public static String format(long bytes) {
        if (bytes < KB) return bytes + " B";
        if (bytes < MB) return String.format("%.2f KB", bytes / (double) KB);
        if (bytes < GB) return String.format("%.2f MB", bytes / (double) MB);
        return String.format("%.2f GB", bytes / (double) GB);
    }

    public static String formatCompact(long bytes) { ... }  // one decimal place
}
```

Four commands originally each had a private copy of this logic. Duplicated code means a bug fix or format change must be made in four places. `ByteFormatter` eliminates the duplication.

`private` constructor follows the standard Java utility class pattern (`java.util.Collections`, `java.util.Objects`). It prevents instantiation and signals that the class has only static methods.

`format()` uses two decimal places — used in download progress and ps output where precision matters. `formatCompact()` uses one — used in `du -h` where brevity is preferred.

---

## 11. Error Handling Patterns

### The standard pattern

Every command follows the same validation-before-execution structure:

```java
// Step 1: argument count
if (args.length < 2) {
    System.err.println("usage: " + usage());
    return ExecutionResult.misuse(context);
}

// Step 2: parse numeric arguments
int count;
try {
    count = Integer.parseInt(args[2]);
} catch (NumberFormatException e) {
    System.err.println("head: invalid line count '" + args[2] + "'");
    return ExecutionResult.fail(context);
}

// Step 3: validate file existence
File file = new File(context.currentDirectory(), args[1]);
if (!file.exists()) {
    System.err.println("head: '" + args[1] + "': No such file");
    return ExecutionResult.fail(context);
}

// Step 4: I/O with try-with-resources
try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
    // ... operate ...
} catch (IOException e) {
    System.err.println("head: " + e.getMessage());
    return ExecutionResult.fail(context);
}

return ExecutionResult.ok(context);
```

**Why this ordering:** The most actionable error is reported first. A wrong argument count is diagnosed immediately without touching the filesystem. A parse error is caught before opening files. File-not-found is caught before attempting I/O.

**`System.err` for all errors:** Command output goes to `System.out`; errors go to `System.err`. This means `cmd > file.txt` captures results without capturing error messages.

### What never to do

```java
// WRONG — swallows exceptions silently
} catch (IOException e) {
    // ignored
}
return ExecutionResult.ok(context);

// WRONG — mixes error messages into stdout
System.out.println("Error: file not found");

// WRONG — returns ok after an error path
System.err.println("something went wrong");
return ExecutionResult.ok(context);   // misleads the && chain
```

Every error path returns `fail` or `misuse`. Returning `ok` after printing an error message would tell the `&&` chain to continue, executing commands after a failed one.

### try-with-resources

```java
try (var fis = new FileInputStream(input);
     var gzos = new GZIPOutputStream(new FileOutputStream(output))) {
    // ...
}   // gzos.close() then fis.close() — in reverse declaration order
```

Resources are closed in reverse order of declaration. For a compression pipeline, the compressor (`gzos`) must flush and close before the source (`fis`) is released. This ordering is guaranteed by the language specification.

### `InterruptedException` — restore interrupt status

```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();  // restore the flag
    System.err.println("exec: interrupted");
    return ExecutionResult.fail(context);
}
```

Catching `InterruptedException` clears the thread's interrupt flag. If you return without restoring it, any upstream code that checks `Thread.interrupted()` will not see the interruption. Restoring it with `Thread.currentThread().interrupt()` is correct even in single-threaded code — it is a habit that prevents subtle bugs if threading is ever introduced.

---

## 12. Java 21 Features in Use

### Records — `ExecutionResult`

```java
public record ExecutionResult(ShellContext context, int exitCode) { }
```

The compiler generates constructor, accessors (`context()`, `exitCode()`), `equals()`, `hashCode()`, and `toString()`. Used as an immutable data carrier with factory methods for readable call sites.

### Sealed interfaces — `Command`

```java
public sealed interface Command permits FileSystemCommands.ListCommand, ... { }
```

Restricts implementors to a declared set. Adding an unlisted implementation fails at compile time. Pattern matching over a sealed type is exhaustive — the compiler warns if a switch is missing a permitted subtype.

### Switch expressions — `CdCommand.resolveTarget()`

```java
return switch (path) {
    case "~"  -> new File(System.getProperty("user.home"));
    case ".." -> { File parent = current.getParentFile(); yield parent != null ? parent : current; }
    default   -> { File f = new File(path); yield f.isAbsolute() ? f : new File(current, path); }
};
```

Switch expressions produce a value. The `->` form has no fall-through between cases. Block arms use `yield` to produce a value. The compiler warns on non-exhaustive switch expressions — a safety property traditional `switch` statements lack.

### `var` — local type inference

```java
var registry = new CommandRegistry();
var ring = new String[capacity];
var scanner = new Scanner(System.in);
```

Used where the type is immediately obvious from the right-hand side. Not used where it would obscure the type (return values of methods with non-obvious types).

### `Optional` and functional dispatch

```java
registry.find(commandName)
    .map(cmd -> cmd.execute(current, parts))
    .orElseGet(() -> ExecutionResult.fail(current));
```

`map` transforms the present case; `orElseGet` handles the absent case. Both arms return `ExecutionResult` — the expression is type-consistent. `orElseGet` takes a `Supplier` and is lazy — the error result is only constructed when the command is not found.

### Method references

```java
tokens.toArray(String[]::new)       // IntFunction<String[]>
lines.forEach(System.out::println)  // Consumer<String>
editScript.forEach(System.out::println)
usages.entrySet().stream().sorted(Map.Entry.comparingByKey())
```

Used where a lambda would only delegate to a single method. `x -> System.out.println(x)` is strictly less readable than `System.out::println`.

### `URI.create().toURL()` instead of `new URL(String)`

```java
var connection = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
```

`new URL(String)` is deprecated in Java 21. The replacement is `URI.create(string).toURL()`, which also validates the URI syntax more strictly before attempting a connection. `IllegalArgumentException` from `URI.create` is caught and reported clearly.

### `Files.newBufferedReader(path)` instead of `new BufferedReader(new FileReader(file))`

`Files.newBufferedReader()` uses UTF-8 by default (since Java 11), returns a `BufferedReader` without wrapping ceremony, and integrates with the `Path` API. The 8 KB internal buffer is appropriate for line-oriented text I/O.

---

## 13. Testing Patterns

### `@TempDir` isolation

Every test that touches the filesystem creates its own isolated `ShellContext`:

```java
@TempDir Path tempDir;
ShellContext ctx;

@BeforeEach
void setup() {
    ctx = new ShellContext(tempDir.toFile());
}
```

JUnit 5 creates a unique temporary directory for each test method and deletes it on completion. No test can pollute another test's filesystem state. This is why `cd` tests that change `context` to a subdirectory do not affect other tests — each test gets its own `ctx`.

### Testing exit codes directly

```java
ExecutionResult r = new TextCommands.GrepCommand().execute(ctx, new String[]{"grep", "xyz", "g.txt"});
assertEquals(1, r.exitCode());   // no match = exit 1, not an error
```

Testing `r.succeeded()` would not catch the distinction between exit 0 and exit 1. For commands where the exit code is semantically meaningful (`grep`, `diff`), assert the specific code.

### Capturing stdout for output assertions

```java
PrintStream old = System.out;
ByteArrayOutputStream b = new ByteArrayOutputStream();
System.setOut(new PrintStream(b));

new UtilityCommands.SortCommand().execute(ctx, new String[]{"sort", "sa.txt"});

System.setOut(old);
assertArrayEquals(new String[]{"apple", "banana", "cherry"}, b.toString().trim().split("\n"));
```

Always restore `System.out` in a `finally` block or ensure the assertion runs before restoration — a failing assertion before `System.setOut(old)` would leave the stream redirected for subsequent tests. In JUnit 5 with `@TempDir`, consider wrapping in try-finally when output capture is involved.

### Testing `&&` chaining via `dispatch()`

```java
CommandRegistry registry = buildRegistry();
ShellContext result = App.dispatch(
    "mkdir chain && cd chain && touch marker.txt",
    ctx, registry
);
assertTrue(result.currentDirectory().getAbsolutePath().endsWith("chain"));
assertTrue(tempDir.resolve("chain/marker.txt").toFile().exists());
```

`App.dispatch()` is `public static` specifically to enable this pattern. Testing through `App.main()` with a mocked stdin is possible but more cumbersome. Direct `dispatch()` calls are cleaner for unit-level chain testing.

### Integration tests via stdin

```java
private String runRepl(String... commands) {
    String input = String.join("\n", commands) + "\nexit\n";
    System.setIn(new ByteArrayInputStream(input.getBytes()));
    System.setOut(new PrintStream(outCapture));
    System.setProperty("user.dir", tempDir.toString());
    App.main(new String[]{});
    // restore streams
    return outCapture.toString();
}
```

Integration tests exercise the full chain: `main()` → `splitOnAnd()` → `Tokenizer` → `CommandRegistry` → `Command` → `ExecutionResult` → context rebind. Unit tests do not cover this chain — only integration tests catch bugs at the boundaries between components.