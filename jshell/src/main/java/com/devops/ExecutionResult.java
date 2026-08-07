package com.devops;

/**
 * Return type for Command.execute().
 * Carries both the (possibly updated) context and the POSIX exit code.
 *
 * Convenience factories:
 *   ExecutionResult.ok(context)       — exit code 0
 *   ExecutionResult.fail(context)     — exit code 1
 *   ExecutionResult.of(context, code) — explicit code
 *
 * Exit code semantics:
 *   0  success
 *   1  failure OR meaningful negative result (grep no-match, diff files-differ)
 *   2  misuse / bad arguments
 */

public record ExecutionResult(ShellContext context, int exitCode) {

    public static ExecutionResult ok(ShellContext context) {
        return new ExecutionResult(context, 0);
    }

    public static ExecutionResult fail(ShellContext context) {
        return new ExecutionResult(context, 1);
    }

    public static ExecutionResult misuse(ShellContext context) {
        return new ExecutionResult(context, 2);
    }

    public static ExecutionResult of(ShellContext context, int code) {
        return new ExecutionResult(context, code);
    }

    public boolean succeeded() {
        return exitCode == 0;
    }
}
