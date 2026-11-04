package dev.youbal.callrec.lib;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * How the app talks to the recorder process it started as the shell user: a few small files in a folder
 * both can reach. No sockets, no binder, no hidden APIs. Requests are serialized process-wide because every
 * ControlChannel instance shares the same cmd/res files.
 *
 * <pre>
 * cmd    token / id / verb / args...   written by the app, deleted by the server when it takes it
 * res    id / answer...                written by the server
 * alive  a time in millis              rewritten by the server every second
 * </pre>
 *
 * The token is a secret kept in the app's private storage and handed to the server on its command line, so
 * another app that can see this folder still cannot give it orders.
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

    private final File dir;
    private final String token;

    ControlChannel(File dir, String token) {
        this.dir = dir;
        this.token = token;
    }

    /** Sends one request and waits for its answer. Throws if the server does not answer in time. */
    String request(String verb, long timeoutMs, String... args) throws IOException, InterruptedException {
        validateRequest(verb, args);
        synchronized (REQUEST_LOCK) {
            dir.mkdirs();
            String id = Long.toString(System.nanoTime(), 36);
            List<String> lines = new ArrayList<>();
            lines.add(token); lines.add(id); lines.add(verb);
            for (String a : args) lines.add(clean(a));
            File res = new File(dir, RES);
            res.delete();
            write(new File(dir, CMD), lines);
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                List<String> answer = read(res);
                if (answer != null && !answer.isEmpty() && answer.get(0).equals(id)) {
                    res.delete();
                    return String.join("\n", answer.subList(1, answer.size()));
                }
                Thread.sleep(40);
            }
            new File(dir, CMD).delete();
            throw new IOException("the recorder process did not answer");
        }
    }

    void beatOwner(long now) {
        List<String> lines = new ArrayList<>(); lines.add(token); lines.add(Long.toString(now));
        try { write(new File(dir, OWNER), lines); } catch (IOException ignored) { /* Expiry safely stops capture. */ }
    }

    boolean ownerAlive(long now, long maxAgeMs) {
        List<String> lines = read(new File(dir, OWNER));
        if (lines == null || lines.size() < 2 || !token.equals(lines.get(0))) return false;
        try { long beat = Long.parseLong(lines.get(1).trim()); return now - beat <= maxAgeMs && beat <= now + 60_000; }
        catch (NumberFormatException e) { return false; }
    }

    void clearOwner() { new File(dir, OWNER).delete(); }

    boolean alive(long now, long maxAgeMs) {
        List<String> lines = read(new File(dir, ALIVE));
        if (lines == null || lines.isEmpty()) return false;
        try { long beat = Long.parseLong(lines.get(0).trim()); return now - beat <= maxAgeMs && beat <= now + 60_000; }
        catch (NumberFormatException e) { return false; }
    }

    /** The waiting request, or null. A request with the wrong token is deleted and ignored. */
    Command poll() {
        File file = new File(dir, CMD);
        List<String> lines = read(file);
        if (lines == null) return null;
        file.delete();
        if (lines.size() < 3 || !lines.get(0).equals(token)) return null;
        String id = lines.get(1);
        String verb = lines.get(2);
        List<String> args = new ArrayList<>(lines.subList(3, lines.size()));
        if (!id.matches("[a-z0-9]{1,32}") || !validCommand(verb, args)) return null;
        return new Command(id, verb, args);
    }

    void respond(String id, String answer) {
        List<String> lines = new ArrayList<>();
        lines.add(id);
        for (String line : (answer == null ? "" : answer).split("\n", -1)) lines.add(line);
        try { write(new File(dir, RES), lines); } catch (IOException ignored) { /* The app will time out and report it. */ }
    }

    void beat(long now) {
        List<String> lines = new ArrayList<>(); lines.add(Long.toString(now));
        try { write(new File(dir, ALIVE), lines); } catch (IOException ignored) { /* Shown as not running. */ }
    }

    void clearAlive() { new File(dir, ALIVE).delete(); }

    private static String clean(String value) { return value == null ? "" : value.replace('\r', ' ').replace('\n', ' '); }

    /** Write beside the destination and rename into place. */
    private static void write(File target, List<String> lines) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.write(temp.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        if (!temp.renameTo(target)) {
            target.delete();
            if (!temp.renameTo(target)) throw new IOException("could not write " + target.getName());
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