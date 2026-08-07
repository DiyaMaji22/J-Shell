package com.devops;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class SearchCommands {

    private SearchCommands() {}

    public static final class FindCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            // Argument parsing: find <pattern> [-r] OR find <dir> -name <pattern>
            File startDir = context.currentDirectory();
            String pattern;
            boolean recursive = false;

            if (args.length >= 4 && args[2].equals("-name")) {
                startDir  = new File(context.currentDirectory(), args[1]);
                pattern   = args[3];
                recursive = true;
            } else if (args.length == 3 && args[2].equals("-r")) {
                pattern   = args[1];
                recursive = true;
            } else {
                pattern = args[1];
            }

            if (!startDir.isDirectory()) {
                System.err.println("find: '" + startDir.getName() + "': No such directory");
                return ExecutionResult.fail(context);
            }

            var count = new AtomicInteger(0);
            findFiles(startDir, pattern, recursive, count);
            System.out.println(count.get() + " match(es) found.");
            return ExecutionResult.ok(context);
        }

        private void findFiles(File dir, String pattern, boolean recursive, AtomicInteger count) {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (file.getName().contains(pattern)) {
                    System.out.println(file.getAbsolutePath());
                    count.incrementAndGet();
                }
                if (file.isDirectory() && recursive) {
                    findFiles(file, pattern, true, count);
                }
            }
        }

        @Override public String name()  { return "find"; }
        @Override public String usage() { return "find <pattern> [-r] | find <dir> -name <pattern>"; }
    }

    public static final class WcCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            boolean linesOnly = false;
            boolean wordsOnly = false;
            boolean charsOnly = false;

            int fileArgIndex = 1;
            if (args[1].equals("-l")) { linesOnly = true; fileArgIndex = 2; }
            else if (args[1].equals("-w")) { wordsOnly = true; fileArgIndex = 2; }
            else if (args[1].equals("-c")) { charsOnly = true; fileArgIndex = 2; }

            if (fileArgIndex >= args.length) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            String fileName = args[fileArgIndex];
            File file = new File(context.currentDirectory(), fileName);
            if (!file.exists()) {
                System.err.println("wc: " + fileName + ": No such file");
                return ExecutionResult.fail(context);
            }

            long lines = 0, words = 0, chars = 0;
            try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines++;
                    chars += line.length();
                    if (!line.isBlank()) {
                        words += line.trim().split("\\s+").length;
                    }
                }
            } catch (IOException e) {
                System.err.println("wc: " + e.getMessage());
                return ExecutionResult.fail(context);
            }

            if (linesOnly)      System.out.printf("%7d %s%n", lines, fileName);
            else if (wordsOnly) System.out.printf("%7d %s%n", words, fileName);
            else if (charsOnly) System.out.printf("%7d %s%n", chars, fileName);
            else                System.out.printf("%7d %7d %7d %s%n", lines, words, chars, fileName);

            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "wc"; }
        @Override public String usage() { return "wc [-l|-w|-c] <file>"; }
    }

    public static final class DiffCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 3) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            File file1 = new File(context.currentDirectory(), args[1]);
            File file2 = new File(context.currentDirectory(), args[2]);

            if (!file1.exists()) { System.err.println("diff: '" + args[1] + "': No such file"); return ExecutionResult.fail(context); }
            if (!file2.exists()) { System.err.println("diff: '" + args[2] + "': No such file"); return ExecutionResult.fail(context); }

            try {
                List<String> a = Files.readAllLines(file1.toPath());
                List<String> b = Files.readAllLines(file2.toPath());
                List<String> editScript = myers(a, b);

                if (editScript.isEmpty()) {
                    System.out.println("Files are identical.");
                    return ExecutionResult.ok(context);
                } else {
                    editScript.forEach(System.out::println);
                    // exit 1 = files differ (POSIX diff convention)
                    return ExecutionResult.of(context, 1);
                }
            } catch (IOException e) {
                System.err.println("diff: " + e.getMessage());
                return ExecutionResult.fail(context);
            }
        }

        /**
         * Myers O(ND) diff algorithm.
         * Produces a minimal edit script: lines prefixed with "-" are only in a,
         * "+" only in b, " " are common. This correctly handles insertions and
         * deletions — unlike line-number alignment which offsets on any insertion.
         */
        private List<String> myers(List<String> a, List<String> b) {
            int n = a.size(), m = b.size();
            int max = n + m;
            if (max == 0) return List.of();

            // v[k] = furthest x reached on diagonal k
            // offset by max so negative diagonals index correctly
            int[] v = new int[2 * max + 1];
            // trace[d] = snapshot of v after d edits
            List<int[]> trace = new ArrayList<>();

            outer:
            for (int d = 0; d <= max; d++) {
                trace.add(v.clone());
                for (int k = -d; k <= d; k += 2) {
                    int idx = k + max;
                    int x;
                    if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                        x = v[idx + 1]; // move down (insertion)
                    } else {
                        x = v[idx - 1] + 1; // move right (deletion)
                    }
                    int y = x - k;
                    // extend snake along matching diagonal
                    while (x < n && y < m && a.get(x).equals(b.get(y))) {
                        x++; y++;
                    }
                    v[idx] = x;
                    if (x >= n && y >= m) break outer;
                }
            }

            return backtrack(a, b, trace, max);
        }

        private List<String> backtrack(List<String> a, List<String> b, List<int[]> trace, int max) {
            List<String> script = new ArrayList<>();
            int x = a.size(), y = b.size();

            for (int d = trace.size() - 1; d >= 0; d--) {
                int[] v = trace.get(d);
                int k   = x - y;
                int idx = k + max;

                int prevK;
                if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                    prevK = k + 1; // came from insertion
                } else {
                    prevK = k - 1; // came from deletion
                }

                int prevX = v[prevK + max];
                int prevY = prevX - prevK;

                // walk back along snake (matching lines)
                while (x > prevX && y > prevY) {
                    script.add(0, "  " + a.get(x - 1));
                    x--; y--;
                }

                if (d > 0) {
                    if (x == prevX) {
                        script.add(0, "+ " + b.get(y - 1)); // insertion
                        y--;
                    } else {
                        script.add(0, "- " + a.get(x - 1)); // deletion
                        x--;
                    }
                }
            }
            // filter out context lines (common lines) for concise output
            return script.stream().filter(l -> !l.startsWith("  ")).toList();
        }

        @Override public String name()  { return "diff"; }
        @Override public String usage() { return "diff <file1> <file2>"; }
    }
}
