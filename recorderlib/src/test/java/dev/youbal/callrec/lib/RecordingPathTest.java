package dev.youbal.callrec.lib;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class RecordingPathTest {
    @Test
    public void acceptsOnlySiblingRecordingsDirectory() throws Exception {
        File base = Files.createTempDirectory("callrec-path").toFile();
        File control = new File(base, ".callrec");
        File recordings = new File(base, "Recordings");
        assertTrue(RecordingPath.isAllowedOutputDir(control, recordings.getPath()));
        assertFalse(RecordingPath.isAllowedOutputDir(control, new File(base, "other").getPath()));
        assertFalse(RecordingPath.isAllowedOutputDir(control, new File(base, "../outside").getPath()));
    }

    @Test
    public void rejectsUnexpectedControlFolder() throws Exception {
        File base = Files.createTempDirectory("callrec-path").toFile();
        File control = new File(base, "not-control");
        assertFalse(RecordingPath.isAllowedOutputDir(control, new File(base, "Recordings").getPath()));
    }
}
