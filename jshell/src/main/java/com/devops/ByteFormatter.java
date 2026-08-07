package com.devops;

/**
 * Shared utility — eliminates formatBytes duplication across 4 command classes.
 */
public final class ByteFormatter {

    private static final long KB = 1024;
    private static final long MB = KB * 1024;
    private static final long GB = MB * 1024;

    private ByteFormatter() {}

    public static String format(long bytes) {
        if (bytes < KB) return bytes + " B";
        if (bytes < MB) return String.format("%.2f KB", bytes / (double) KB);
        if (bytes < GB) return String.format("%.2f MB", bytes / (double) MB);
        return String.format("%.2f GB", bytes / (double) GB);
    }

    public static String formatCompact(long bytes) {
        if (bytes < KB) return bytes + " B";
        if (bytes < MB) return String.format("%.1f KB", bytes / (double) KB);
        if (bytes < GB) return String.format("%.1f MB", bytes / (double) MB);
        return String.format("%.1f GB", bytes / (double) GB);
    }
}
