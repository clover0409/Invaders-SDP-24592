package records;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Requirement 3.8 ghost storage (Jira KAN-27). All files live in a temporary directory. */
class GhostStoreTest {

    @TempDir
    Path directory;

    private final List<String> warnings = new ArrayList<String>();
    private final Handler capture = new Handler() {
        @Override public void publish(final LogRecord record) { warnings.add(record.getMessage()); }
        @Override public void flush() { }
        @Override public void close() { }
    };
    private final Logger logger = Logger.getLogger(GhostStore.class.getName());

    @BeforeEach
    void captureLogs() { this.logger.addHandler(this.capture); }

    @AfterEach
    void releaseLogs() { this.logger.removeHandler(this.capture); }

    @Test
    void eachModeRoundTripsInItsOwnFileWithoutTouchingTheProfile() throws Exception {
        Path profile = this.directory.resolve("profile.properties");
        new ProfileStore(profile).write(new ProfileSnapshot(
                Arrays.asList(new RunRecord("r", 5, 0, 0L, 1L)), new LinkedHashMap<String, Long>()));
        byte[] profileBytes = Files.readAllBytes(profile);

        GhostStore store = new GhostStore(this.directory);
        GhostTrack campaign = track(GhostTrack.Mode.CAMPAIGN, "camp", 3000);
        GhostTrack infinite = track(GhostTrack.Mode.INFINITE, "inf", 50);
        store.saveAsync(campaign).get(10, TimeUnit.SECONDS);
        store.saveAsync(infinite).get(10, TimeUnit.SECONDS);
        store.close();

        assertFalse(store.fileFor(GhostTrack.Mode.CAMPAIGN).equals(store.fileFor(GhostTrack.Mode.INFINITE)));
        assertEquals(Optional.of(campaign), new GhostStore(this.directory).load(GhostTrack.Mode.CAMPAIGN));
        assertEquals(Optional.of(infinite), new GhostStore(this.directory).load(GhostTrack.Mode.INFINITE));
        assertArrayEquals(profileBytes, Files.readAllBytes(profile), "records profile is separate");
        assertTrue(temporaryFiles().isEmpty());
    }

    @Test
    void saveAsyncReturnsBeforeTheWriteFinishes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GhostStore store = new GhostStore(this.directory, (source, target) -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        });
        CompletableFuture<Void> pending = store.saveAsync(track(GhostTrack.Mode.CAMPAIGN, "slow", 1));
        assertFalse(pending.isDone(), "the game thread is not blocked by disk I/O");
        release.countDown();
        pending.get(10, TimeUnit.SECONDS);
        store.close();
        assertTrue(store.load(GhostTrack.Mode.CAMPAIGN).isPresent());
    }

    @Test
    void missingFileDisablesGhostQuietly() {
        assertEquals(Optional.empty(), new GhostStore(this.directory).load(GhostTrack.Mode.INFINITE));
        assertTrue(this.warnings.isEmpty(), "a first run without a ghost is normal");
    }

    @Test
    void corruptFileDisablesGhostLogsAndIsKept() throws IOException {
        GhostStore store = new GhostStore(this.directory);
        Path file = store.fileFor(GhostTrack.Mode.CAMPAIGN);
        byte[] corrupt = "version=1\nmode=campaign\nsample.count=3\nsample.0=1".getBytes(StandardCharsets.UTF_8);
        Files.write(file, corrupt);

        assertEquals(Optional.empty(), store.load(GhostTrack.Mode.CAMPAIGN));
        assertEquals(1, this.warnings.size());
        assertTrue(this.warnings.get(0).startsWith("Ghost disabled"), this.warnings.get(0));
        assertArrayEquals(corrupt, Files.readAllBytes(file), "unreadable ghost is left for inspection");

        RecordsService records = new RecordsService(this.directory.resolve("profile.properties"));
        assertFalse(records.isWritesBlocked(), "records profile is unaffected by a bad ghost");
        records.close();
    }

    @Test
    void wrongVersionOrModeDisablesGhost() throws Exception {
        GhostStore store = new GhostStore(this.directory);
        store.save(track(GhostTrack.Mode.CAMPAIGN, "c", 10));
        Path campaign = store.fileFor(GhostTrack.Mode.CAMPAIGN);
        String text = new String(Files.readAllBytes(campaign), StandardCharsets.UTF_8);

        Files.write(store.fileFor(GhostTrack.Mode.INFINITE), text.getBytes(StandardCharsets.UTF_8));
        assertEquals(Optional.empty(), store.load(GhostTrack.Mode.INFINITE), "mode mismatch");

        Files.write(campaign, text.replace("version=1", "version=9").getBytes(StandardCharsets.UTF_8));
        assertEquals(Optional.empty(), store.load(GhostTrack.Mode.CAMPAIGN), "future version");
        assertEquals(2, this.warnings.size());
    }

    @Test
    void failedWriteKeepsThePreviousGhost() throws Exception {
        GhostTrack previous = track(GhostTrack.Mode.INFINITE, "previous", 700);
        new GhostStore(this.directory).save(previous);

        GhostStore failing = new GhostStore(this.directory, (source, target) -> {
            throw new IOException("simulated rename failure");
        });
        GhostTrack better = track(GhostTrack.Mode.INFINITE, "better", 900);
        assertThrows(IOException.class, () -> failing.save(better));
        ExecutionException async = assertThrows(ExecutionException.class,
                () -> failing.saveAsync(better).get(10, TimeUnit.SECONDS));
        assertNotNull(async.getCause());
        failing.close();

        assertTrue(failing.getLastError().startsWith("Ghost save failed; previous ghost was kept"));
        assertEquals(Optional.of(previous), new GhostStore(this.directory).load(GhostTrack.Mode.INFINITE));
        assertTrue(temporaryFiles().isEmpty(), "failed writes clean up their temporary file");
    }

    @Test
    void trackTimelineFollowsActiveTime() {
        GhostTrack track = new GhostTrack(GhostTrack.Mode.CAMPAIGN, "t", 99,
                new int[] { 10, 20, 30 }, new int[] { 0, 5, 40 });
        assertEquals(200L, track.getDurationMillis());
        assertEquals(0, track.sampleIndexAt(99L));
        assertEquals(1, track.sampleIndexAt(100L));
        assertEquals(5, track.scoreAt(150L));
        assertFalse(track.isFinishedAt(200L));
        assertTrue(track.isFinishedAt(201L));
        assertEquals(99, track.scoreAt(5000L), "after its run ended the ghost compares with its final score");
        assertThrows(IllegalArgumentException.class, () -> new GhostTrack(GhostTrack.Mode.CAMPAIGN,
                "bad", 0, new int[2], new int[1]));
    }

    private static GhostTrack track(final GhostTrack.Mode mode, final String id, final int samples) {
        int[] positions = new int[samples];
        int[] scores = new int[samples];
        for (int i = 0; i < samples; i++) {
            positions[i] = 200 + (i % 50) * 3;
            scores[i] = i * 10;
        }
        return new GhostTrack(mode, id, samples * 10, positions, scores);
    }

    private List<Path> temporaryFiles() throws IOException {
        List<Path> result = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.directory, "*.tmp")) {
            for (Path path : stream)
                result.add(path);
        }
        return result;
    }
}
