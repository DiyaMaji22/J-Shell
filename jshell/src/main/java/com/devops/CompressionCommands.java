package com.devops;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class CompressionCommands {

    private static final int BUFFER_SIZE = 8192;

    private CompressionCommands() {}

    public static final class ZipCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            boolean recursive = args.length > 1 && args[1].equals("-r");
            int zipNameIdx  = recursive ? 2 : 1;
            int firstFileIdx = recursive ? 3 : 2;

            if (args.length < firstFileIdx + 1) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            String zipName = args[zipNameIdx].endsWith(".zip")
                ? args[zipNameIdx] : args[zipNameIdx] + ".zip";
            File zipFile = new File(context.currentDirectory(), zipName);

            try (var zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                for (int i = firstFileIdx; i < args.length; i++) {
                    File file = new File(context.currentDirectory(), args[i]);
                    if (!file.exists()) {
                        System.err.println("zip: warning: '" + args[i] + "' not found");
                        continue;
                    }
                    if (file.isDirectory() && recursive) {
                        addDirectory(file, file.getName(), zos);
                    } else if (file.isFile()) {
                        addFile(file, file.getName(), zos);
                    }
                }
                System.out.println("Created: " + zipName);
            } catch (IOException e) {
                System.err.println("zip: " + e.getMessage());
                return ExecutionResult.fail(context);
            }
            return ExecutionResult.ok(context);
        }

        private void addFile(File file, String entryName, ZipOutputStream zos) throws IOException {
            zos.putNextEntry(new ZipEntry(entryName));
            Files.copy(file.toPath(), zos);
            zos.closeEntry();
        }

        private void addDirectory(File dir, String base, ZipOutputStream zos) throws IOException {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File file : files) {
                String entryName = base + "/" + file.getName();
                if (file.isDirectory()) {
                    addDirectory(file, entryName, zos);
                } else {
                    addFile(file, entryName, zos);
                }
            }
        }

        @Override public String name()  { return "zip"; }
        @Override public String usage() { return "zip [-r] <output.zip> <file...>"; }
    }

    public static final class UnzipCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            File zipFile = new File(context.currentDirectory(), args[1]);
            File destDir = args.length > 2
                ? new File(context.currentDirectory(), args[2])
                : context.currentDirectory();

            if (!zipFile.exists()) {
                System.err.println("unzip: '" + args[1] + "': No such file");
                return ExecutionResult.fail(context);
            }

            String destCanonical;
            try {
                destCanonical = destDir.getCanonicalPath();
            } catch (IOException e) {
                System.err.println("unzip: " + e.getMessage());
                return ExecutionResult.fail(context);
            }

            try (var zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                byte[] buffer = new byte[BUFFER_SIZE];

                while ((entry = zis.getNextEntry()) != null) {
                    File target = new File(destDir, entry.getName());

                    // Zip slip protection
                    if (!target.getCanonicalPath().startsWith(destCanonical + File.separator)
                            && !target.getCanonicalPath().equals(destCanonical)) {
                        System.err.println("unzip: blocked unsafe entry: " + entry.getName());
                        zis.closeEntry();
                        continue;
                    }

                    if (entry.isDirectory()) {
                        target.mkdirs();
                    } else {
                        target.getParentFile().mkdirs();
                        try (var fos = new FileOutputStream(target)) {
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                        }
                        System.out.println("Extracted: " + entry.getName());
                    }
                    zis.closeEntry();
                }
                System.out.println("Done.");
            } catch (IOException e) {
                System.err.println("unzip: " + e.getMessage());
                return ExecutionResult.fail(context);
            }
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "unzip"; }
        @Override public String usage() { return "unzip <file.zip> [destination]"; }
    }

    public static final class GzipCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }
            File input = new File(context.currentDirectory(), args[1]);
            if (!input.exists()) {
                System.err.println("gzip: '" + args[1] + "': No such file");
                return ExecutionResult.fail(context);
            }
            File output = new File(context.currentDirectory(), args[1] + ".gz");
            try (var fis = new FileInputStream(input);
                 var gzos = new GZIPOutputStream(new FileOutputStream(output))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = fis.read(buffer)) > 0) gzos.write(buffer, 0, len);
                System.out.println(args[1] + " -> " + output.getName());
            } catch (IOException e) {
                System.err.println("gzip: " + e.getMessage());
                return ExecutionResult.fail(context);
            }
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "gzip"; }
        @Override public String usage() { return "gzip <file>"; }
    }

    public static final class GunzipCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }
            String fileName = args[1];
            if (!fileName.endsWith(".gz")) {
                System.err.println("gunzip: '" + fileName + "': unknown suffix");
                return ExecutionResult.fail(context);
            }
            File input = new File(context.currentDirectory(), fileName);
            if (!input.exists()) {
                System.err.println("gunzip: '" + fileName + "': No such file");
                return ExecutionResult.fail(context);
            }
            String outputName = fileName.substring(0, fileName.length() - 3);
            File output = new File(context.currentDirectory(), outputName);
            try (var gzis = new GZIPInputStream(new FileInputStream(input));
                 var fos = new FileOutputStream(output)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = gzis.read(buffer)) > 0) fos.write(buffer, 0, len);
                System.out.println(fileName + " -> " + outputName);
            } catch (IOException e) {
                System.err.println("gunzip: " + e.getMessage());
                return ExecutionResult.fail(context);
            }
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "gunzip"; }
        @Override public String usage() { return "gunzip <file.gz>"; }
    }
}
