package records;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Re-verification of requirement 3.4 on team main (Jira KAN-11, KAN-12).
 * Every fault is injected into a JUnit temporary directory, never into a real
 * player profile.
 */
class ProfileRecoveryTest {

    @TempDir
    Path directory;

    // ---- KAN-11: restart recovery and versioned profile I/O ----

    @Test
    void restartRestoresRecordsAndAchievementsExactly() {
        Path profile = this.directory.resolve("profile.properties");
        RecordsService first = new RecordsService(profile);
        first.startRun("run-a");
        for (int i = 0; i < 20; i++)
            first.enemyDefeated("run-a", "enemy-" + i);
        first.activeDuration("run-a", 60000L);
        first.completeRun(new RunRecord("run-a", 300, 20, 60000L, 1000L));
        first.startRun("run-b");
        first.completeRun(new RunRecord("run-b", 450, 3, 9000L, 2000L));
        first.close();
        assertNull(first.getSaveError(), "all queued saves finish");

        RecordsService restored = new RecordsService(profile);
        try {
            assertFalse(restored.isWritesBlocked());
            assertNull(restored.getLoadWarning());
            assertEquals(ids(first.getTopRuns()), ids(restored.getTopRuns()));
            assertEquals(450, restored.getHighestScore());
            for (Achievement achievement : Achievement.values())
                assertEquals(first.getUnlockTime(achievement),
                        restored.getUnlockTime(achievement), achievement.getId());
        } finally {
            restored.close();
        }
    }

    @Test
    void profileIsVersionedUtf8AndRoundTripsNonAsciiIds() throws IOException {
        Path profile = this.directory.resolve("profile.properties");
        String id = "담조곤-运行-ß";
        new ProfileStore(profile).write(snapshot(new RunRecord(id, 7, 1, 10L, 5L)));

        byte[] bytes = Files.readAllBytes(profile);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.contains("version=1"), "profile declares its format version");
        assertTrue(text.contains(id), "non-ASCII text is stored as UTF-8");
        assertEquals(id, new ProfileStore(profile).load().getRecords().get(0).getRunId());
    }

    @Test
    void unsupportedVersionIsPreservedAndBlocksWrites() throws IOException {
        Path profile = this.directory.resolve("profile.properties");
        new ProfileStore(profile).write(snapshot(new RunRecord("old", 1, 0, 0L, 1L)));
        String future = new String(Files.readAllBytes(profile), StandardCharsets.UTF_8)
                .replace("version=1", "version=2");
        Files.write(profile, future.getBytes(StandardCharsets.UTF_8));
        byte[] original = Files.readAllBytes(profile);

        RecordsService service = new RecordsService(profile);
        assertTrue(service.isWritesBlocked());
        assertTrue(service.getSaveError().contains("Unsupported profile version"),
                service.getSaveError());
        service.completeRun(new RunRecord("new", 99, 0, 0L, 2L));
        service.close();

        assertArrayEquals(original, Files.readAllBytes(profile),
                "a profile from another version is never overwritten");
        assertTrue(corruptCopies().size() >= 1, "a recovery copy is kept");
    }

    @Test
    void closeDrainsEveryQueuedWrite() {
        Path profile = this.directory.resolve("profile.properties");
        RecordsService service = new RecordsService(profile);
        for (int i = 0; i < 50; i++)
            service.completeRun(new RunRecord("queued-" + i, i, 0, 0L, i + 1L));
        service.close();
        assertNull(service.getSaveError());

        RecordsService restored = new RecordsService(profile);
        assertEquals(50, restored.getRecordCount());
        assertEquals(49, restored.getHighestScore());
        restored.close();
    }

    @Test
    void saveFailureIsReportedAndPreviousProfileKept() throws IOException {
        Path profile = this.directory.resolve("profile.properties");
        new ProfileStore(profile).write(snapshot(new RunRecord("kept", 10, 0, 0L, 1L)));
        byte[] original = Files.readAllBytes(profile);

        RecordsService service = new RecordsService(profile);
        withReadOnlyDirectory(() -> {
            service.completeRun(new RunRecord("lost", 20, 0, 0L, 2L));
            service.close();
        });
        assertTrue(service.getSaveError().startsWith("Save failed; previous data was preserved"),
                service.getSaveError());
        assertArrayEquals(original, Files.readAllBytes(profile));
    }

    // ---- KAN-12: corruption, backup recovery and write-failure protection ----

    @Test
    void truncatedPrimaryIsKeptAndBackupRestoredWithWarning() throws IOException {
        Path profile = writeTwoGenerations();
        Files.write(profile, new byte[0]);

        RecordsService service = new RecordsService(profile);
        assertFalse(service.isWritesBlocked());
        assertEquals(1, service.getRecordCount(), "backup holds the previous generation");
        assertNotNull(service.getLoadWarning());
        assertTrue(service.getLoadWarning().contains("newer progress may be unavailable"));
        service.close();
        assertFalse(corruptCopies().isEmpty(), "truncated primary is preserved");
        assertEquals(1, new ProfileStore(profile).load().getRecords().size(),
                "the restored data is written back as a readable primary");
    }

    @Test
    void missingPrimaryIsRestoredFromBackup() throws IOException {
        Path profile = writeTwoGenerations();
        Files.delete(profile);

        ProfileStore store = new ProfileStore(profile);
        assertEquals(1, store.load().getRecords().size());
        assertNotNull(store.getLoadWarning());
        assertTrue(Files.exists(profile));
    }

    @Test
    void bothFilesUnreadableAreNeverOverwritten() throws IOException {
        Path profile = this.directory.resolve("profile.properties");
        Path backup = this.directory.resolve("profile.properties.bak");
        byte[] primaryBytes = "version=1\nrecord.count=oops".getBytes(StandardCharsets.UTF_8);
        byte[] backupBytes = "\u0000\u0001garbage".getBytes(StandardCharsets.UTF_8);
        Files.write(profile, primaryBytes);
        Files.write(backup, backupBytes);

        assertThrows(IOException.class, () -> new ProfileStore(profile).load());
        RecordsService service = new RecordsService(profile);
        assertTrue(service.isWritesBlocked());
        assertTrue(service.getSaveError().startsWith("Profile load failed"));
        service.completeRun(new RunRecord("would-be-empty", 0, 0, 0L, 1L));
        service.close();

        assertArrayEquals(primaryBytes, Files.readAllBytes(profile));
        assertArrayEquals(backupBytes, Files.readAllBytes(backup));
    }

    @Test
    void failedPrimaryReplacementKeepsPreviousProfile() throws IOException {
        Path profile = writeTwoGenerations();
        byte[] before = Files.readAllBytes(profile);
        ProfileStore store = new ProfileStore(profile);

        withReadOnlyDirectory(() -> assertThrows(IOException.class,
                () -> store.write(snapshot(new RunRecord("never", 1, 0, 0L, 9L)))));

        assertArrayEquals(before, Files.readAllBytes(profile));
        assertEquals(2, new ProfileStore(profile).load().getRecords().size());
    }

    @Test
    void validBackupIsUsedEvenWhenItCannotBeResaved() throws IOException {
        Path profile = writeTwoGenerations();
        Files.write(profile, "broken".getBytes(StandardCharsets.UTF_8));
        ProfileStore store = new ProfileStore(profile);

        ProfileSnapshot[] loaded = new ProfileSnapshot[1];
        withReadOnlyDirectory(() -> loaded[0] = store.load());

        assertEquals(1, loaded[0].getRecords().size(),
                "readable backup data is not discarded because re-saving failed");
        assertTrue(store.getLoadWarning().contains("could not be re-saved"),
                store.getLoadWarning());
    }

    // ---- helpers ----

    /** Writes two generations so the backup holds one record and the primary two. */
    private Path writeTwoGenerations() throws IOException {
        Path profile = this.directory.resolve("profile.properties");
        ProfileStore store = new ProfileStore(profile);
        RunRecord older = new RunRecord("older", 10, 1, 1000L, 1L);
        store.write(snapshot(older));
        store.write(snapshot(older, new RunRecord("newer", 20, 2, 2000L, 2L)));
        return profile;
    }

    private static ProfileSnapshot snapshot(final RunRecord... records) {
        return new ProfileSnapshot(Arrays.asList(records), new LinkedHashMap<String, Long>());
    }

    private static List<String> ids(final List<RunRecord> records) {
        String[] result = new String[records.size()];
        for (int i = 0; i < result.length; i++)
            result[i] = records.get(i).getRunId();
        return Arrays.asList(result);
    }

    private List<Path> corruptCopies() throws IOException {
        List<Path> result = new java.util.ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.directory,
                "profile.properties.corrupt-*")) {
            for (Path path : stream)
                result.add(path);
        }
        return result;
    }

    private interface IoAction {
        void run() throws IOException;
    }

    /** Runs an action while the profile directory refuses new files (POSIX only). */
    private void withReadOnlyDirectory(final IoAction action) throws IOException {
        assumeTrue(Files.getFileStore(this.directory).supportsFileAttributeView("posix"),
                "needs POSIX permissions");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(this.directory);
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Path probe = this.directory.resolve("probe");
            boolean stillWritable;
            try {
                Files.createFile(probe);
                Files.delete(probe);
                stillWritable = true;
            } catch (IOException expected) {
                stillWritable = false;
            }
            assumeTrue(!stillWritable, "running with privileges that ignore permissions");
            action.run();
        } finally {
            Files.setPosixFilePermissions(this.directory, original);
        }
    }
}
