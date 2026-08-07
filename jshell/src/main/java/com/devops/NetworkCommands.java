package com.devops;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Enumeration;

public final class NetworkCommands {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 10_000;
    private static final int BUFFER_SIZE        = 8192;

    private NetworkCommands() {}

    public static final class PingCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            String host = args[1];
            int count;
            try {
                count = args.length > 2 ? Integer.parseInt(args[2]) : 4;
                if (count <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                System.err.println("ping: invalid count '" + args[2] + "'");
                return ExecutionResult.misuse(context);
            }

            System.out.println("PING " + host + " (" + count + " packets)");
            System.out.println();

            InetAddress address;
            try {
                address = InetAddress.getByName(host);
            } catch (UnknownHostException e) {
                System.err.println("ping: cannot resolve '" + host + "'");
                return ExecutionResult.fail(context);
            }

            int successful = 0;
            long totalMs = 0;

            for (int i = 1; i <= count; i++) {
                try {
                    long start = System.currentTimeMillis();
                    boolean reachable = address.isReachable(5000);
                    long elapsed = System.currentTimeMillis() - start;

                    if (reachable) {
                        System.out.printf("Reply from %s (%s): time=%dms%n",
                            host, address.getHostAddress(), elapsed);
                        successful++;
                        totalMs += elapsed;
                    } else {
                        // Note: isReachable may fail without root — ICMP is unreliable from JVM
                        System.out.println("Request timed out.");
                    }

                    if (i < count) Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    System.err.println("ping: " + e.getMessage());
                }
            }

            System.out.println();
            int loss = ((count - successful) * 100) / count;
            System.out.printf("--- %s ping statistics ---%n", host);
            System.out.printf("%d packets, %d received, %d%% loss%n", count, successful, loss);
            if (successful > 0) {
                System.out.printf("avg %.0fms%n", totalMs / (double) successful);
            }
            return successful > 0 ? ExecutionResult.ok(context) : ExecutionResult.fail(context);
        }

        @Override public String name()  { return "ping"; }
        @Override public String usage() { return "ping <host> [count]"; }
    }

    public static final class WgetCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            String urlString = args[1];
            String fileName  = args.length > 2 ? args[2]
                : urlString.substring(urlString.lastIndexOf('/') + 1);
            if (fileName.isBlank()) fileName = "index.html";

            try {
                var connection = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");

                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    System.err.println("wget: server returned HTTP " + code);
                    return ExecutionResult.fail(context);
                }

                long total = connection.getContentLengthLong();
                File output = new File(context.currentDirectory(), fileName);

                try (InputStream in  = connection.getInputStream();
                     var out = new FileOutputStream(output)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    long downloaded = 0;
                    long lastPrint = System.currentTimeMillis();

                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;

                        long now = System.currentTimeMillis();
                        if (now - lastPrint > 500) {
                            String progress = total > 0
                                ? String.format("%d%%", downloaded * 100 / total)
                                : ByteFormatter.format(downloaded);
                            System.out.print("\r" + progress);
                            lastPrint = now;
                        }
                    }
                }

                System.out.printf("%nSaved: %s (%s)%n", fileName, ByteFormatter.format(output.length()));
            } catch (IllegalArgumentException e) {
                System.err.println("wget: invalid URL '" + urlString + "'");
                return ExecutionResult.fail(context);
            } catch (IOException e) {
                System.err.println("wget: " + e.getMessage());
                return ExecutionResult.fail(context);
            }
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "wget"; }
        @Override public String usage() { return "wget <url> [filename]"; }
    }

    public static final class CurlCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            if (args.length < 2) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            boolean saveToFile = args[1].equals("-o");
            if (saveToFile && args.length < 4) {
                System.err.println("usage: " + usage());
                return ExecutionResult.misuse(context);
            }

            String fileName  = saveToFile ? args[2] : null;
            String urlString = saveToFile ? args[3] : args[1];

            try {
                var connection = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");

                int code = connection.getResponseCode();
                System.out.println("HTTP " + code);

                try (var in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    if (saveToFile) {
                        File out = new File(context.currentDirectory(), fileName);
                        try (var writer = new FileWriter(out)) {
                            in.transferTo(writer);
                        }
                        System.out.println("Saved: " + fileName);
                    } else {
                        in.lines().forEach(System.out::println);
                    }
                }
            } catch (IllegalArgumentException e) {
                System.err.println("curl: invalid URL '" + urlString + "'");
                return ExecutionResult.fail(context);
            } catch (IOException e) {
                System.err.println("curl: " + e.getMessage());
                return ExecutionResult.fail(context);
            }
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "curl"; }
        @Override public String usage() { return "curl [-o <file>] <url>"; }
    }

    public static final class IfconfigCommand implements Command {

        @Override
        public ExecutionResult execute(ShellContext context, String[] args) {
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                if (interfaces == null) {
                    System.out.println("No network interfaces found.");
                    return ExecutionResult.ok(context);
                }
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    System.out.printf("%s: <%s> mtu %d%n",
                        ni.getName(),
                        ni.isUp() ? "UP" : "DOWN",
                        ni.getMTU());
                    var addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        System.out.println("  inet " + addresses.nextElement().getHostAddress());
                    }
                }
            } catch (IOException e) {
                System.err.println("ifconfig: " + e.getMessage());
                return ExecutionResult.fail(context);
            }
            return ExecutionResult.ok(context);
        }

        @Override public String name()  { return "ifconfig"; }
        @Override public String usage() { return "ifconfig"; }
    }
}
