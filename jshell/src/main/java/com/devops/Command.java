package com.devops;

/**
 * Sealed interface — every permitted subtype is declared here.
 * Adding a command without listing it is a compile error.
 *
 * execute() returns ExecutionResult which carries:
 *   - the updated ShellContext (new instance on cd, same instance otherwise)
 *   - a POSIX exit code (0 = success, 1 = failure/negative result, 2 = misuse)
 *
 * This allows the REPL to support && chaining and future scripting modes
 * without losing the immutable context propagation model.
 */
public sealed interface Command
    permits FileSystemCommands.ListCommand,
            FileSystemCommands.PwdCommand,
            FileSystemCommands.CdCommand,
            FileSystemCommands.MkdirCommand,
            FileManipulationCommands.TouchCommand,
            FileManipulationCommands.RmCommand,
            FileManipulationCommands.CatCommand,
            TextCommands.EchoCommand,
            TextCommands.GrepCommand,
            TextCommands.HelpCommand,
            AdvancedFileCommands.CpCommand,
            AdvancedFileCommands.MvCommand,
            SystemCommands.HistoryCommand,
            SystemCommands.WhoamiCommand,
            SystemCommands.DateCommand,
            SystemCommands.ClearCommand,
            SearchCommands.FindCommand,
            SearchCommands.WcCommand,
            SearchCommands.DiffCommand,
            CompressionCommands.ZipCommand,
            CompressionCommands.UnzipCommand,
            CompressionCommands.GzipCommand,
            CompressionCommands.GunzipCommand,
            NetworkCommands.PingCommand,
            NetworkCommands.WgetCommand,
            NetworkCommands.CurlCommand,
            NetworkCommands.IfconfigCommand,
            ProcessCommands.PsCommand,
            ProcessCommands.ExecCommand,
            ProcessCommands.EnvCommand,
            ProcessCommands.UnameCommand,
            UtilityCommands.SortCommand,
            UtilityCommands.UniqCommand,
            UtilityCommands.ChecksumCommand,
            UtilityCommands.DuCommand,
            UtilityCommands.HeadCommand,
            UtilityCommands.TailCommand {

    ExecutionResult execute(ShellContext context, String[] args);

    default String name()  { return ""; }
    default String usage() { return ""; }
}
