package records;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ghost file storage for requirement 3.8 (Jira KAN-27).
 *
 * <p>One file per mode ({@code ghost-campaign.properties},
 * {@code ghost-infinite.properties}), separate from the records profile.
 * Writes follow the {@link ProfileStore} approach: temporary file, read-back
 * validation, then atomic replace, so a failed write keeps the previous ghost.
 * {@link #saveAsync} runs on a background thread and never blocks play.
 * A missing, corrupt or wrong-version file only disables the ghost: it is
 * logged, left on disk for inspection, and the records profile is untouched.
 *
 * <p>Deciding whether a run should replace the ghost (completed run with a
 * new high score for its mode) belongs to the caller.
 */
public final class GhostStore {
    private static final Logger LOGGER = Logger.getLogger(GhostStore.class.getName());
    private static final String VERSION = "1";

    /** Replace step, injectable so tests can simulate a failing rename on any OS. */
    interface Replacer {
        void replace(Path source, Path target) throws IOException;
    }

    private final Path directory;
    private final Replacer replacer;
    private final ExecutorService writer;
    private volatile String lastError;

    public GhostStore(final Path directory) {
        this(directory, GhostStore::atomicReplace);
    }

    GhostStore(final Path directory, final Replacer replacer) {
        this.directory = directory;
        this.replacer = replacer;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "records-ghost-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** File that holds the ghost for a mode. */
    public Path fileFor(final GhostTrack.Mode mode) {
        return this.directory.resolve("ghost-" + mode.getId() + ".properties");
    }

    /**
     * Loads the ghost for a mode. Returns empty when no ghost should be shown;
     * never throws for missing or invalid data.
     */
    public Optional<GhostTrack> load(final GhostTrack.Mode mode) {
        Path file = fileFor(mode);
        try {
            return Optional.of(read(file, mode));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException | RuntimeException invalid) {
            LOGGER.log(Level.WARNING, "Ghost disabled; unreadable ghost file kept at "
                    + file + ": " + invalid.getMessage());
            return Optional.empty();
        }
    }

    /** Writes the ghost synchronously; on failure the previous file is kept. */
    public synchronized void save(final GhostTrack track) throws IOException {
        Files.createDirectories(this.directory);
        Path target = fileFor(track.getMode());
        Path temporary = Files.createTempFile(this.directory, "ghost-", ".tmp");
        boolean moved = false;
        try {
            try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                toProperties(track).store(out, "Space Invaders ghost run");
            }
            if (!track.equals(read(temporary, track.getMode())))
                throw new IOException("Ghost read-back did not match the written data.");
            this.replacer.replace(temporary, target);
            moved = true;
        } finally {
            if (!moved)
                Files.deleteIfExists(temporary);
        }
    }

    /** Queues a write on the background thread; failures are logged and kept in {@link #getLastError()}. */
    public CompletableFuture<Void> saveAsync(final GhostTrack track) {
        return CompletableFuture.runAsync(() -> {
            try {
                save(track);
                this.lastError = null;
            } catch (IOException exception) {
                this.lastError = "Ghost save failed; previous ghost was kept: " + exception.getMessage();
                LOGGER.log(Level.WARNING, this.lastError);
                throw new java.io.UncheckedIOException(exception);
            }
        }, this.writer);
    }

    public String getLastError() { return this.lastError; }

    /** Finishes queued writes (call on shutdown). */
    public void close() {
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(10, TimeUnit.SECONDS)) {
                this.writer.shutdownNow();
                this.lastError = "Ghost save did not finish before shutdown.";
            }
        } catch (InterruptedException exception) {
            this.writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static Properties toProperties(final GhostTrack track) {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("mode", track.getMode().getId());
        properties.setProperty("run.id", track.getRunId());
        properties.setProperty("final.score", Integer.toString(track.getFinalScore()));
        properties.setProperty("interval.ms", Long.toString(GhostTrack.SAMPLE_INTERVAL_MILLIS));
        properties.setProperty("sample.count", Integer.toString(track.getSampleCount()));
        for (int i = 0; i < track.getSampleCount(); i++)
            properties.setProperty("sample." + i, track.getPosition(i) + "," + track.getScore(i));
        return properties;
    }

    private static GhostTrack read(final Path file, final GhostTrack.Mode expectedMode)
            throws IOException {
        Properties properties = new Properties();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Ghost contains invalid properties.", exception);
        }
        if (!VERSION.equals(properties.getProperty("version")))
            throw new IOException("Unsupported ghost version.");
        if (GhostTrack.Mode.fromId(properties.getProperty("mode")) != expectedMode)
            throw new IOException("Ghost belongs to another mode.");
        if (!Long.toString(GhostTrack.SAMPLE_INTERVAL_MILLIS).equals(properties.getProperty("interval.ms")))
            throw new IOException("Unsupported ghost sample interval.");
        int count = parseInt(properties, "sample.count");
        if (count < 0 || count > GhostTrack.MAX_SAMPLES)
            throw new IOException("Invalid ghost sample count.");
        int[] positions = new int[count];
        int[] scores = new int[count];
        for (int i = 0; i < count; i++) {
            String value = properties.getProperty("sample." + i);
            int comma = value == null ? -1 : value.indexOf(',');
            if (comma < 0)
                throw new IOException("Missing ghost sample " + i);
            try {
                positions[i] = Integer.parseInt(value.substring(0, comma).trim());
                scores[i] = Integer.parseInt(value.substring(comma + 1).trim());
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid ghost sample " + i, exception);
            }
        }
        try {
            return new GhostTrack(expectedMode, properties.getProperty("run.id"),
                    parseInt(properties, "final.score"), positions, scores);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid ghost header.", exception);
        }
    }

    private static int parseInt(final Properties properties, final String key) throws IOException {
        String value = properties.getProperty(key);
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid ghost field: " + key, exception);
        }
    }

    private static void atomicReplace(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
