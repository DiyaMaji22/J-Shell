package com.devops;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public final class App {

    public static void main(String[] args) {
        ShellContext context = new ShellContext(new File(System.getProperty("user.dir")));
        var registry = new CommandRegistry();
        registerCommands(registry);

        System.out.println("Welcome to J-Shell — type 'help' or 'exit'.");
        System.out.println();

        try (var scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print(context.currentDirectory().getAbsolutePath() + " > ");

                if (!scanner.hasNextLine()) break;

                String input = scanner.nextLine().trim();
                if (input.isEmpty()) continue;

                context.addHistory(input);

                if (input.equals("exit")) {
                    System.out.println("Goodbye!");
                    break;
                }

                context = dispatch(input, context, registry);
            }
        }
    }

    /**
     * Supports && chaining: "mkdir foo && cd foo && touch bar"
     * Each stage runs only if the previous stage exited with code 0.
     * The final context (possibly updated by cd) is returned.
     */
    public static ShellContext dispatch(String input, ShellContext context, CommandRegistry registry) {
        List<String> stages = splitOnAnd(input);

        for (String stage : stages) {
            String[] parts = Tokenizer.tokenize(stage.trim());
            if (parts.length == 0) continue;

            String commandName = parts[0];
            // Capture as effectively final for use inside lambdas
            ShellContext current = context;

            ExecutionResult result = registry.find(commandName)
                .map(cmd -> {
                    try {
                        return cmd.execute(current, parts);
                    } catch (Exception e) {
                        System.err.println(commandName + ": unexpected error: " + e.getMessage());
                        return ExecutionResult.fail(current);
                    }
                })
                .orElseGet(() -> {
                        System.err.println("j-shell: command not found: " + commandName);
                    return ExecutionResult.fail(current);
                });

            // Rebind — cd produces a new ShellContext instance, all others return the same
            context = result.context();

            // && semantics: stop the chain on first non-zero exit
            if (!result.succeeded() && stages.size() > 1) {
                break;
            }
        }

        return context;
    }

    /**
     * Splits "cmd1 && cmd2 && cmd3" into ["cmd1 ", " cmd2 ", " cmd3"].
     * Does not split on && inside quoted strings.
     */
    private static List<String> splitOnAnd(String input) {
        List<String> stages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '"' && !inSingle)       inDouble = !inDouble;
            else if (c == '\'' && !inDouble)  inSingle = !inSingle;
            else if (c == '&' && !inDouble && !inSingle
                     && i + 1 < input.length() && input.charAt(i + 1) == '&') {
                stages.add(current.toString());
                current.setLength(0);
                i++; // skip second &
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) stages.add(current.toString());
        return stages;
    }

    private static void registerCommands(CommandRegistry registry) {
        registry.register("ls",       new FileSystemCommands.ListCommand());
        registry.register("pwd",      new FileSystemCommands.PwdCommand());
        registry.register("cd",       new FileSystemCommands.CdCommand());
        registry.register("mkdir",    new FileSystemCommands.MkdirCommand());

        registry.register("touch",    new FileManipulationCommands.TouchCommand());
        registry.register("rm",       new FileManipulationCommands.RmCommand());
        registry.register("cat",      new FileManipulationCommands.CatCommand());

        registry.register("echo",     new TextCommands.EchoCommand());
        registry.register("grep",     new TextCommands.GrepCommand());
        registry.register("help",     new TextCommands.HelpCommand(registry));

        registry.register("cp",       new AdvancedFileCommands.CpCommand());
        registry.register("mv",       new AdvancedFileCommands.MvCommand());

        registry.register("history",  new SystemCommands.HistoryCommand());
        registry.register("whoami",   new SystemCommands.WhoamiCommand());
        registry.register("date",     new SystemCommands.DateCommand());
        registry.register("clear",    new SystemCommands.ClearCommand());

        registry.register("find",     new SearchCommands.FindCommand());
        registry.register("wc",       new SearchCommands.WcCommand());
        registry.register("diff",     new SearchCommands.DiffCommand());

        registry.register("zip",      new CompressionCommands.ZipCommand());
        registry.register("unzip",    new CompressionCommands.UnzipCommand());
        registry.register("gzip",     new CompressionCommands.GzipCommand());
        registry.register("gunzip",   new CompressionCommands.GunzipCommand());

        registry.register("ping",     new NetworkCommands.PingCommand());
        registry.register("wget",     new NetworkCommands.WgetCommand());
        registry.register("curl",     new NetworkCommands.CurlCommand());
        registry.register("ifconfig", new NetworkCommands.IfconfigCommand());

        registry.register("ps",       new ProcessCommands.PsCommand());
        registry.register("exec",     new ProcessCommands.ExecCommand());
        registry.register("env",      new ProcessCommands.EnvCommand());
        registry.register("uname",    new ProcessCommands.UnameCommand());

        registry.register("sort",     new UtilityCommands.SortCommand());
        registry.register("uniq",     new UtilityCommands.UniqCommand());
        registry.register("checksum", new UtilityCommands.ChecksumCommand());
        registry.register("du",       new UtilityCommands.DuCommand());
        registry.register("head",     new UtilityCommands.HeadCommand());
        registry.register("tail",     new UtilityCommands.TailCommand());
    }
}
