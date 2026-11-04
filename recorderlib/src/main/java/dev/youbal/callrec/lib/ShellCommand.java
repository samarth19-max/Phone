package dev.youbal.callrec.lib;

/** Small shell-quoting helper for values passed as single command arguments. */
final class ShellCommand {
    private ShellCommand() {
    }

    /**
     * Quotes an arbitrary value as one POSIX shell word.
     *
     * <p>Single quotes preserve every character except a single quote, which is emitted using the
     * standard close-quote, escaped-quote, reopen-quote sequence.</p>
     */
    static String quote(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
