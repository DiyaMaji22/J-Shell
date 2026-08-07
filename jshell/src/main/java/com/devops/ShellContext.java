package com.devops;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable-by-default session state.
 * currentDirectory is a canonical path invariant — always fully resolved.
 * history is append-only; the public view is unmodifiable.
 * cd produces a new ShellContext via withDirectory() — no mutation.
 */
public final class ShellContext {

    private final File currentDirectory;
    private final List<String> history;

    public ShellContext(File startDirectory) {
        this(canonicalize(startDirectory), new ArrayList<>());
    }

    private ShellContext(File directory, List<String> history) {
        this.currentDirectory = directory;
        this.history = history;
    }

    public File currentDirectory() {
        return currentDirectory;
    }

    /**
     * Returns a new ShellContext with the directory changed.
     * Caller must pass a canonical file (CdCommand is responsible for this).
     */
    public ShellContext withDirectory(File canonical) {
        return new ShellContext(canonical, history);
    }

    public void addHistory(String command) {
        history.add(command);
    }

    public List<String> history() {
        return Collections.unmodifiableList(history);
    }

    private static File canonicalize(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }
}
