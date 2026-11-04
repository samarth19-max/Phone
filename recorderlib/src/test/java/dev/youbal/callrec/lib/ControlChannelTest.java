package dev.youbal.callrec.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ControlChannelTest {

    private static File dir() throws IOException {
        return Files.createTempDirectory("callrec-ctl").toFile();
    }


    private static void writeCommand(File dir, String token, String id, String verb, String... args) throws IOException {
        writeCommandAt(dir, token, id, System.currentTimeMillis(), verb, args);
    }

    private static void writeCommandAt(File dir, String token, String id, long issuedAt, String verb, String... args) throws IOException {
        List<String> fields = new ArrayList<>();
        fields.add(id);
        fields.add(Long.toString(issuedAt));
        fields.add(verb);
        for (String arg : args) fields.add(arg);
        fields.add(ControlChannel.mac(token, "cmd", fields));
        Files.write(new File(dir, "cmd").toPath(), String.join("\n", fields).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A stand-in for the recorder process: answers one request in a second thread. */
    private static Thread server(ControlChannel channel, String answer, int[] seen) {
        Thread t = new Thread(() -> {
            long end = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < end) {
                ControlChannel.Command c = channel.poll();
                if (c != null) { seen[0]++; channel.respond(c.id, c.verb + ":" + c.arg(0) + ":" + c.arg(1) + (answer == null ? "" : answer)); return; }
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        });
        t.start();
        return t;
    }

    @Test
    public void requestGetsTheServersAnswer() throws Exception {
        File d = dir();
        ControlChannel app = new ControlChannel(d, "secret");
        ControlChannel shell = new ControlChannel(d, "secret");
        int[] seen = {0};
        Thread t = server(shell, "", seen);
        assertEquals("START:/sdcard/x:+911234", app.request("START", 3000, "/sdcard/x", "+911234", "out"));
        t.join();
        assertEquals(1, seen[0]);
    }

    @Test
    public void multiLineAnswersSurvive() throws Exception {
        File d = dir();
        ControlChannel app = new ControlChannel(d, "secret");
        ControlChannel shell = new ControlChannel(d, "secret");
        Thread t = server(shell, "\nsecond line", new int[1]);
        assertEquals("STOP::\nsecond line", app.request("STOP", 3000));
        t.join();
    }

    @Test
    public void aWrongTokenIsIgnored() throws Exception {
        File d = dir();
        ControlChannel stranger = new ControlChannel(d, "guess");
        ControlChannel shell = new ControlChannel(d, "secret");
        try { stranger.request("START", 200, "a", "b", "c"); fail("nobody should answer"); } catch (IOException expected) { /* timed out */ }
        assertNull(shell.poll());
        // And a real request after a stranger's still works.
        ControlChannel app = new ControlChannel(d, "secret");
        int[] seen = {0};
        Thread t = server(shell, "", seen);
        assertEquals("PING::", app.request("PING", 3000));
        t.join();
        assertEquals(1, seen[0]);
    }

    @Test
    public void newlinesCannotSmuggleExtraArguments() throws Exception {
        File d = dir();
        ControlChannel app = new ControlChannel(d, "secret");
        ControlChannel shell = new ControlChannel(d, "secret");
        final String[] got = new String[1];
        Thread t = new Thread(() -> {
            long end = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < end) {
                ControlChannel.Command c = shell.poll();
                if (c != null) { got[0] = c.args.size() + "|" + c.arg(0); shell.respond(c.id, "ok"); return; }
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        });
        t.start();
        app.request("START", 3000, "dir\nSTOP\nevil");
        t.join();
        assertEquals("1|dir STOP evil", got[0]);
    }

    @Test
    public void heartbeatIsFreshThenStale() throws Exception {
        File d = dir();
        ControlChannel shell = new ControlChannel(d, "secret");
        ControlChannel app = new ControlChannel(d, "secret");
        assertFalse(app.alive(1_000_000, 3000));
        shell.beat(1_000_000);
        assertTrue(app.alive(1_002_000, 3000));
        assertFalse(app.alive(1_010_000, 3000));
        shell.clearAlive();
        assertFalse(app.alive(1_000_500, 3000));
    }
    @Test
    public void ownerLeaseRequiresMatchingTokenAndExpires() throws Exception {
        File d = dir();
        ControlChannel app = new ControlChannel(d, "secret");
        ControlChannel stranger = new ControlChannel(d, "wrong-token");
        assertFalse(app.ownerAlive(1_000_000, 5000));
        app.beatOwner(1_000_000);
        assertTrue(app.ownerAlive(1_002_000, 5000));
        assertFalse(app.ownerAlive(1_006_000, 5000));
        assertFalse(stranger.ownerAlive(1_002_000, 5000));
        app.clearOwner();
        assertFalse(app.ownerAlive(1_001_000, 5000));
    }

    @Test
    public void ownerLeaseRejectsFutureTimestamps() throws Exception {
        File d = dir();
        ControlChannel app = new ControlChannel(d, "secret");
        app.beatOwner(2_000_000);
        assertFalse(app.ownerAlive(1_000_000, 5000));
    }

    @Test
    public void requestsFromDifferentInstancesAreSerialized() throws Exception {
        File d = dir();
        ControlChannel first = new ControlChannel(d, "secret");
        ControlChannel second = new ControlChannel(d, "secret");
        ControlChannel shell = new ControlChannel(d, "secret");
        final String[] answers = new String[2];
        Thread server = new Thread(() -> {
            int handled = 0;
            long end = System.currentTimeMillis() + 6000;
            while (handled < 2 && System.currentTimeMillis() < end) {
                ControlChannel.Command command = shell.poll();
                if (command != null) {
                    shell.respond(command.id, command.verb);
                    handled++;
                } else {
                    try { Thread.sleep(10); } catch (InterruptedException e) { return; }
                }
            }
        });
        Thread a = new Thread(() -> {
            try { answers[0] = first.request("PING", 5000); } catch (Exception e) { answers[0] = "error"; }
        });
        Thread b = new Thread(() -> {
            try { answers[1] = second.request("STOP", 5000); } catch (Exception e) { answers[1] = "error"; }
        });
        server.start();
        a.start();
        b.start();
        a.join(6000);
        b.join(6000);
        server.join(6000);
        assertEquals("PING", answers[0]);
        assertEquals("STOP", answers[1]);
    }

    @Test
    public void shellRejectsUnknownCommandsAndWrongArgumentCounts() throws Exception {
        File d = dir();
        ControlChannel shell = new ControlChannel(d, "secret");
        String id = "0123456789abcdef0123456789abcdef";
        writeCommand(d, "secret", id, "EXEC", "whoami");
        assertNull(shell.poll());
        writeCommand(d, "secret", id, "START", "only-one-argument");
        assertNull(shell.poll());
    }

    @Test
    public void oversizedControlFilesAreIgnored() throws Exception {
        File d = dir();
        ControlChannel shell = new ControlChannel(d, "secret");
        byte[] oversized = new byte[20 * 1024];
        Files.write(new File(d, "cmd").toPath(), oversized);
        assertNull(shell.poll());
    }


    @Test
    public void shellRejectsTamperedAndReplayedCommands() throws Exception {
        File d = dir();
        ControlChannel shell = new ControlChannel(d, "secret");
        String id = "abcdef0123456789abcdef0123456789";
        writeCommand(d, "secret", id, "PING");
        assertEquals("PING", shell.poll().verb);
        writeCommand(d, "secret", id, "PING");
        assertNull(shell.poll());

        String otherId = "0123456789abcdef0123456789abcdef";
        writeCommand(d, "secret", otherId, "PING");
        File cmd = new File(d, "cmd");
        String tampered = new String(Files.readAllBytes(cmd.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                .replace("PING", "STOP");
        Files.write(cmd.toPath(), tampered.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNull(shell.poll());
    }

    @Test
    public void ownerLeaseDoesNotWriteTheSecretIntoSharedStorage() throws Exception {
        File d = dir();
        ControlChannel app = new ControlChannel(d, "secret");
        app.beatOwner(1_000_000);
        String owner = new String(Files.readAllBytes(new File(d, "owner").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(owner.contains("secret"));
        assertEquals(2, owner.split("\n").length);
        Files.copy(new File(d, "owner").toPath(), new File(d, "alive").toPath());
        assertFalse(app.alive(1_000_000, 3000));
    }


    @Test
    public void shellRejectsExpiredAuthenticatedCommands() throws Exception {
        File d = dir();
        ControlChannel shell = new ControlChannel(d, "secret");
        String id = "0123456789abcdef0123456789abcdef";
        writeCommandAt(d, "secret", id, System.currentTimeMillis() - 20_000, "PING");
        assertNull(shell.poll());
    }

}
