package dev.youbal.callrec.lib;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ShellCommandTest {
    @Test
    public void quotesEmptyAndOrdinaryValues() {
        assertEquals("''", ShellCommand.quote(""));
        assertEquals("'safe/path with spaces'", ShellCommand.quote("safe/path with spaces"));
    }

    @Test
    public void escapesSingleQuotesWithoutLeavingTheArgument() {
        assertEquals("'a'\\''; touch /tmp/pwned;\\''b'", ShellCommand.quote("a'; touch /tmp/pwned;'b"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNull() {
        ShellCommand.quote(null);
    }
}
