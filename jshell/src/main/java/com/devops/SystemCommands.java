package com.devops;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class SystemCommands {

    private SystemCommands() {}

    public static final class HistoryCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            List<String> history = context.history();
            if (history.isEmpty()) {
                System.out.println("No history.");
                return ExecutionResult.ok(context);
            }
            for (int i = 0; i < history.size(); i++) {
                System.out.printf("%5d  %s%n", i + 1, history.get(i));
            }
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "history"; }
        @Override public String usage() { return "history"; }
    }

    public static final class WhoamiCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            System.out.println(System.getProperty("user.name", "unknown"));
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "whoami"; }
        @Override public String usage() { return "whoami"; }
    }

    public static final class DateCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            System.out.println(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "date"; }
        @Override public String usage() { return "date"; }
    }

    public static final class ClearCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "clear"; }
        @Override public String usage() { return "clear"; }
    }
}
