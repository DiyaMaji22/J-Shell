package com.devops;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ProcessCommands {

    private ProcessCommands() {}

    public static final class PsCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            boolean verbose = args.length > 1 && args[1].equals("-v");

            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            MemoryMXBean  memory  = ManagementFactory.getMemoryMXBean();
            ThreadMXBean  threads = ManagementFactory.getThreadMXBean();

            System.out.println("PID:      " + runtime.getPid());
            System.out.println("Uptime:   " + formatUptime(runtime.getUptime()));
            System.out.println("Started:  " + Instant.ofEpochMilli(runtime.getStartTime()));

            var heap = memory.getHeapMemoryUsage();
            System.out.printf("Heap:     %s / %s%n",
                ByteFormatter.format(heap.getUsed()),
                ByteFormatter.format(heap.getMax()));
            System.out.println("Threads:  " + threads.getThreadCount());

            if (verbose) {
                System.out.println();
                System.out.println("Active threads:");
                for (long id : threads.getAllThreadIds()) {
                    var info = threads.getThreadInfo(id);
                    if (info != null) {
                        System.out.printf("  [%d] %-30s %s%n", id, info.getThreadName(), info.getThreadState());
                    }
                }
            }
            return ExecutionResult.ok(context);
        }

        private String formatUptime(long ms) {
            long s = ms / 1000, m = s / 60, h = m / 60, d = h / 24;
            if (d > 0) return d + "d " + (h % 24) + "h " + (m % 60) + "m";
            if (h > 0) return h + "h " + (m % 60) + "m " + (s % 60) + "s";
            if (m > 0) return m + "m " + (s % 60) + "s";
            return s + "s";
        }

        @Override public String name()  { return "ps"; }
        @Override public String usage() { return "ps [-v]"; }
    }

    public static final class ExecCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            String[] command = new String[args.length - 1];
            System.arraycopy(args, 1, command, 0, command.length);

            try {
                var pb = new ProcessBuilder(command);
                pb.directory(context.currentDirectory());
                pb.redirectErrorStream(true);

                Process process = pb.start();

                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    reader.lines().forEach(System.out::println);
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) System.err.println("exited with code " + exitCode);
                return ExecutionResult.of(context, exitCode);

            } catch (IOException e) {
                System.err.println("exec: " + e.getMessage());
                return ExecutionResult.fail(context);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("exec: interrupted");
                return ExecutionResult.fail(context);
            }
        }

        @Override public String name()  { return "exec"; }
        @Override public String usage() { return "exec <command> [args...]"; }
    }

    public static final class EnvCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            Map<String, String> env = System.getenv();

            if (args.length > 1) {
                String value = env.get(args[1]);
                if (value == null) {
                    System.err.println("env: '" + args[1] + "': not set");
                    return ExecutionResult.fail(context);
                }
                System.out.println(args[1] + "=" + value);
            } else {
                List<String> keys = new ArrayList<>(env.keySet());
                Collections.sort(keys);
                keys.forEach(k -> System.out.println(k + "=" + env.get(k)));
            }
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "env"; }
        @Override public String usage() { return "env [variable]"; }
    }

    public static final class UnameCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            Runtime rt = Runtime.getRuntime();
            System.out.printf("%-20s %s%n", "OS:",       System.getProperty("os.name"));
            System.out.printf("%-20s %s%n", "Version:",  System.getProperty("os.version"));
            System.out.printf("%-20s %s%n", "Arch:",     System.getProperty("os.arch"));
            System.out.printf("%-20s %s%n", "Java:",     System.getProperty("java.version"));
            System.out.printf("%-20s %s%n", "User:",     System.getProperty("user.name"));
            System.out.printf("%-20s %d%n", "CPUs:",     rt.availableProcessors());
            System.out.printf("%-20s %s%n", "Free mem:", ByteFormatter.format(rt.freeMemory()));
            System.out.printf("%-20s %s%n", "Max mem:",  ByteFormatter.format(rt.maxMemory()));
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "uname"; }
        @Override public String usage() { return "uname"; }
    }
}
