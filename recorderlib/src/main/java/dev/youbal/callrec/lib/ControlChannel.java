package dev.youbal.callrec.lib;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * How the app talks to the recorder process it started as the shell user: a few small files in a folder
 * both can reach. No sockets, no binder, no hidden APIs. Requests are serialized process-wide because every
 * ControlChannel instance shares the same cmd/res files.
 *
 * <pre>
 * cmd    id / timestamp / verb / args / HMAC  written by the app, deleted by the server when it takes it
 * res    id / answer...                written by the server
 * alive  a time in millis              rewritten by the server every second
 * </pre>
 *
 * The token is kept in app-private storage and handed to the server on its command line. Shared files contain only
 * HMAC-SHA256 authenticators, not the token itself.
 */
final class ControlChannel {

    static final class Command {
        final String id, verb;
        final List<String> args;
        Command(String id, String verb, List<String> args) { this.id = id; this.verb = verb; this.args = args; }
        String arg(int i) { return i < args.size() ? args.get(i) : ""; }
    }

    private static final String CMD = "cmd", RES = "res", ALIVE = "alive", OWNER = "owner";
    private static final Object REQUEST_LOCK = new Object();
    private static final int MAX_PROTOCOL_BYTES = 16 * 1024;
    private static final int MAX_ARGUMENT_LENGTH = 4096;
    private static final long MAX_COMMAND_AGE_MS = 10_000;
    private static final long MAX_FUTURE_SKEW_MS = 60_000;

    private final File dir;
    private final String token;
    private final LinkedHashSet<String> consumedCommandIds = new LinkedHashSet<>();

    ControlChannel(File dir, String token) {
        this.dir = dir;
        this.token = token;
    }

    /** Sends one request and waits for its answer. Throws if the server does not answer in time. */
    String request(String verb, long timeoutMs, String... args) throws IOException, InterruptedException {
        validateRequest(verb, args);
        synchronized (REQUEST_LOCK) {
            dir.mkdirs();
            String id = UUID.randomUUID().toString().replace("-", "");
            List<String> lines = new ArrayList<>();
            lines.add(id);
            lines.add(Long.toString(System.currentTimeMillis()));
            lines.add(verb);
            for (String a : args) lines.add(clean(a));
            lines.add(mac(token, "cmd", lines));
            File res = new File(dir, RES);
            res.delete();
            write(new File(dir, CMD), lines);
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                List<String> answer = read(res);
                if (answer != null && answer.size() >= 3 && answer.get(0).equals(id)
                        && authentic(token, "res", answer.subList(0, answer.size() - 1), answer.get(answer.size() - 1))) {
                    res.delete();
                    return String.join("\n", answer.subList(1, answer.size() - 1));
                }
                Thread.sleep(40);
            }
            new File(dir, CMD).delete();
            throw new IOException("the recorder process did not answer");
        }
    }

    void beatOwner(long now) {
        List<String> lines = new ArrayList<>(); lines.add(Long.toString(now));
        lines.add(mac(token, "owner", lines));
        try { write(new File(dir, OWNER), lines); } catch (IOException ignored) { /* Expiry safely stops capture. */ }
    }

    boolean ownerAlive(long now, long maxAgeMs) {
        List<String> lines = read(new File(dir, OWNER));
        if (lines == null || lines.size() != 2 || !authentic(token, "owner", lines.subList(0, 1), lines.get(1))) return false;
        try { long beat = Long.parseLong(lines.get(0).trim()); return now - beat <= maxAgeMs && beat <= now + 60_000; }
        catch (NumberFormatException e) { return false; }
    }

    void clearOwner() { new File(dir, OWNER).delete(); }

    boolean alive(long now, long maxAgeMs) {
        List<String> lines = read(new File(dir, ALIVE));
        if (lines == null || lines.size() != 2 || !authentic(token, "alive", lines.subList(0, 1), lines.get(1))) return false;
        try { long beat = Long.parseLong(lines.get(0).trim()); return now - beat <= maxAgeMs && beat <= now + 60_000; }
        catch (NumberFormatException e) { return false; }
    }

    /** The waiting request, or null. Invalid, unauthenticated, and replayed requests are discarded. */
    Command poll() {
        File file = new File(dir, CMD);
        List<String> lines = read(file);
        if (lines == null) return null;
        file.delete();
        if (lines.size() < 4
                || !authentic(token, "cmd", lines.subList(0, lines.size() - 1), lines.get(lines.size() - 1))) return null;
        String id = lines.get(0);
        long issuedAt;
        try {
            issuedAt = Long.parseLong(lines.get(1));
        } catch (NumberFormatException malformedTimestamp) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - issuedAt > MAX_COMMAND_AGE_MS || issuedAt > now + MAX_FUTURE_SKEW_MS) return null;
        String verb = lines.get(2);
        List<String> args = new ArrayList<>(lines.subList(3, lines.size() - 1));
        if (!id.matches("[a-f0-9]{32}") || !validCommand(verb, args) || consumedCommandIds.contains(id)) return null;
        consumedCommandIds.add(id);
        if (consumedCommandIds.size() > 128) {
            Iterator<String> oldest = consumedCommandIds.iterator();
            oldest.next();
            oldest.remove();
        }
        return new Command(id, verb, args);
    }

    void respond(String id, String answer) {
        List<String> lines = new ArrayList<>();
        lines.add(id);
        for (String line : (answer == null ? "" : answer).split("\n", -1)) lines.add(line);
        lines.add(mac(token, "res", lines));
        try { write(new File(dir, RES), lines); } catch (IOException ignored) { /* The app will time out and report it. */ }
    }

    void beat(long now) {
        List<String> lines = new ArrayList<>(); lines.add(Long.toString(now));
        lines.add(mac(token, "alive", lines));
        try { write(new File(dir, ALIVE), lines); } catch (IOException ignored) { /* Shown as not running. */ }
    }

    void clearAlive() { new File(dir, ALIVE).delete(); }

    private static String clean(String value) { return value == null ? "" : value.replace('\r', ' ').replace('\n', ' '); }

    /** Validate on both sides of the shared-file boundary. */
    private static void validateRequest(String verb, String... args) throws IOException {
        List<String> values = new ArrayList<>();
        for (String arg : args) {
            String value = clean(arg);
            if (value.length() > MAX_ARGUMENT_LENGTH) throw new IOException("control argument is too long");
            values.add(value);
        }
        if (!validCommand(verb, values)) throw new IOException("unsupported control command or argument count");
    }

    /** HMAC the protocol fields so the shared external-storage files never contain the secret itself. */
    static String mac(String token, String domain, List<String> fields) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = hmac.doFinal((domain + "\n" + String.join("\n", fields)).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC is unavailable", e);
        }
    }

    private static boolean authentic(String token, String domain, List<String> fields, String suppliedMac) {
        if (suppliedMac == null || !suppliedMac.matches("[a-f0-9]{64}")) return false;
        byte[] expected = mac(token, domain, fields).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = suppliedMac.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    private static boolean validCommand(String verb, List<String> args) {
        if (args.size() > 3) return false;
        for (String arg : args) if (arg == null || arg.length() > MAX_ARGUMENT_LENGTH) return false;
        if ("START".equals(verb)) return args.size() == 3;
        return ("STOP".equals(verb) || "RESET".equals(verb) || "QUIT".equals(verb) || "PING".equals(verb))
                && args.isEmpty();
    }

    /** Write beside the destination and rename into place. */
    private static void write(File target, List<String> lines) throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("control directory is unavailable");
        }
        File temp = File.createTempFile(target.getName(), ".tmp", parent);
        try {
            Files.write(temp.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static List<String> read(File file) {
        try {
            if (!file.isFile()) return null;
            long length = file.length();
            if (length <= 0 || length > MAX_PROTOCOL_BYTES) return null;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n", -1)) lines.add(line);
            return lines;
        } catch (IOException e) {
            return null;
        }
    }
}