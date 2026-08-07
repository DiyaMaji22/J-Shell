package com.devops;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class FileManipulationCommands {

    private FileManipulationCommands() {}

    public static final class TouchCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }
            File file = new File(context.currentDirectory(), args[1]);
            try {
                if (!file.createNewFile()) {
                    file.setLastModified(System.currentTimeMillis());
                }
            } catch (IOException e) {
                System.err.println("touch: " + e.getMessage());
                return ExecutionResult.fail(context);
            }
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "touch"; }
        @Override public String usage() { return "touch <file>"; }
    }

    public static final class RmCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }
            boolean recursive = args[1].equals("-r") || args[1].equals("-rf");
            String target = recursive ? (args.length > 2 ? args[2] : null) : args[1];

            if (target == null) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            File file = new File(context.currentDirectory(), target);
            if (!file.exists()) {
                System.err.println("rm: cannot remove '" + target + "': No such file or directory");
                return ExecutionResult.fail(context);
            }

            if (file.isDirectory() && !recursive) {
                System.err.println("rm: '" + target + "' is a directory (use -r)");
                return ExecutionResult.fail(context);
            }

            if (!deleteRecursive(file)) {
                System.err.println("rm: failed to remove '" + target + "'");
                return ExecutionResult.fail(context);
            }
            return ExecutionResult.ok(context);
        }

        private boolean deleteRecursive(File file) {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!deleteRecursive(child)) return false;
                    }
                }
            }
            return file.delete();
        }

        @Override public String name()  { return "rm"; }
        @Override public String usage() { return "rm [-r] <file|directory>"; }
    }

    public static final class CatCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }
            File file = new File(context.currentDirectory(), args[1]);
            if (!file.exists()) {
                System.err.println("cat: " + args[1] + ": No such file");
                return ExecutionResult.fail(context);
            }
            // Stream line by line — avoids loading large files into memory
            try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                System.err.println("cat: " + e.getMessage());
                return ExecutionResult.fail(context);
            }
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "cat"; }
        @Override public String usage() { return "cat <file>"; }
    }
}
