import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * The three commands the app image runs besides the server (ADR 0118): the image is distroless, so it has no shell,
 * no {@code curl}, no {@code cat} and no {@code tar}, and what a deployment once ran through them it runs here, on the
 * image's own {@code java}. Built and smoke-tested by the Dockerfile's {@code image-prep} stage; one file, the JDK
 * alone, no package, so nothing of the server's classpath is loaded and a run costs one small JVM start.
 *
 * <pre>
 *   java -jar /opt/veyq/image-commands.jar health [URL]        exit 0 when URL answers 2xx/3xx (default the app's /api/setup)
 *   java -jar /opt/veyq/image-commands.jar place-key PATH      stdin into PATH, born 0600 as the caller, by an atomic rename
 *   java -jar /opt/veyq/image-commands.jar read-file PATH      PATH to stdout; exit 3 when there is no such file
 * </pre>
 *
 * {@code health} is the Compose healthcheck, which `curl -fsS` was: a 4xx/5xx or no answer is unhealthy, a redirect
 * is not followed and counts as healthy. It speaks HTTP/1.1 over a plain socket rather than through
 * {@code HttpURLConnection}, so no proxy property a deployment sets for the server (JAVA_TOOL_OPTIONS reaches this
 * JVM too) can route the probe elsewhere. {@code place-key} and {@code read-file} are how {@code veyqctl} and the Run
 * pages put leg (c) into the app's volume and read the erasure ledger (or the key) out of it: run as the image's user,
 * so a key is born the application's without root. Usage errors exit 2.
 */
public final class ImageCommands {

    private static final String DEFAULT_HEALTH_URL = "http://127.0.0.1:8080/api/setup";
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 4_000;

    private ImageCommands() {
    }

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "";
        try {
            switch (command) {
                case "health" -> System.exit(health(args.length > 1 ? args[1] : DEFAULT_HEALTH_URL));
                case "place-key" -> System.exit(args.length == 2 ? placeKey(Path.of(args[1])) : usage());
                case "read-file" -> System.exit(args.length == 2 ? readFile(Path.of(args[1])) : usage());
                default -> System.exit(usage());
            }
        } catch (IOException | RuntimeException e) {
            System.err.println(command + ": " + e);
            System.exit(1);
        }
    }

    private static int usage() {
        System.err.println("usage: image-commands.jar health [URL] | place-key PATH | read-file PATH");
        return 2;
    }

    static int health(String url) throws IOException {
        URI uri = URI.create(url);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null) {
            System.err.println("health: only http://host[:port]/path is probed, not " + url);
            return 2;
        }
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String host = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + port;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = statusLine(socket.getInputStream());
            String[] parts = status.split(" ", 3);
            int code = parts.length >= 2 && parts[0].startsWith("HTTP/") ? parseCode(parts[1]) : -1;
            System.out.println(url + ": " + status);
            return code >= 200 && code < 400 ? 0 : 1;
        } catch (IOException e) {
            System.err.println(url + ": " + e);
            return 1;
        }
    }

    private static int parseCode(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The response's first line, without reading the body: the probe judges the status alone. */
    private static String statusLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int b = in.read(); b != -1 && b != '\n' && line.length() < 1024; b = in.read()) {
            if (b != '\r') {
                line.append((char) b);
            }
        }
        return line.toString();
    }

    /**
     * stdin into [target] through a sibling `<target>.veyqctl`, created 0600 and renamed over [target] — the shell's
     * `umask 077 && cat > "$1.veyqctl" && mv -f "$1.veyqctl" "$1"`, plus one refusal: an empty stdin is no key, and
     * writing it would replace a key with a file the server refuses at its start.
     */
    static int placeKey(Path target) throws IOException {
        byte[] key = System.in.readAllBytes();
        if (key.length == 0) {
            System.err.println("place-key: stdin is empty — no key was written to " + target);
            return 1;
        }
        Path staged = target.resolveSibling(target.getFileName() + ".veyqctl");
        // A leftover of a run that died is replaced; a writer racing this one makes createFile throw, and the run stops.
        Files.deleteIfExists(staged);
        Files.createFile(staged, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        // The attribute is filtered by the process umask; set the mode again so 0600 holds whatever the umask is.
        Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"));
        Files.write(staged, key, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return 0;
    }

    /** [file] to stdout, or exit 3 when it is not a regular file — `test -f "$1" || exit 3; cat "$1"`. */
    static int readFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            System.err.println("read-file: no file at " + file);
            return 3;
        }
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(System.out);
        }
        System.out.flush();
        return 0;
    }
}
