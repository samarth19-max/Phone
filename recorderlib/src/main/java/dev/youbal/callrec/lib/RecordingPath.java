package dev.youbal.callrec.lib;

import java.io.File;
import java.io.IOException;

/** Validates the output location accepted by the privileged shell recorder. */
final class RecordingPath {
    private RecordingPath() {
    }

    /**
     * The control folder is <app-external-files>/.callrec and recordings belong beside it in
     * <app-external-files>/Recordings. Canonical-path comparison rejects traversal and symlink escapes.
     */
    static boolean isAllowedOutputDir(File controlDir, String requestedPath) {
        if (controlDir == null || requestedPath == null || requestedPath.isEmpty()) return false;
        try {
            File control = controlDir.getCanonicalFile();
            if (!".callrec".equals(control.getName())) return false;
            File base = control.getParentFile();
            if (base == null) return false;
            File root = base.getCanonicalFile();
            File expected = new File(root, "Recordings").getCanonicalFile();
            String rootPrefix = root.getPath() + File.separator;
            if (!expected.getPath().startsWith(rootPrefix)) return false;
            return expected.equals(new File(requestedPath).getCanonicalFile());
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
