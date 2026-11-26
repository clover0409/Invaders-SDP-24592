package records;

import java.util.Arrays;

/**
 * Immutable replay of one completed run for requirement 3.8: the player
 * ship's horizontal position and the score, sampled every
 * {@link #SAMPLE_INTERVAL_MILLIS} of active play.
 */
public final class GhostTrack {

    /** Game modes that each keep their own ghost. */
    public enum Mode {
        CAMPAIGN("campaign"), INFINITE("infinite");

        private final String id;

        Mode(final String id) { this.id = id; }

        public String getId() { return this.id; }

        public static Mode fromId(final String id) {
            for (Mode mode : values())
                if (mode.id.equals(id))
                    return mode;
            return null;
        }
    }

    /** Active time between two samples. */
    public static final long SAMPLE_INTERVAL_MILLIS = 100L;
    /** Upper bound that keeps a damaged count from allocating huge arrays (about 2.7 h). */
    public static final int MAX_SAMPLES = 100000;

    private final Mode mode;
    private final String runId;
    private final int finalScore;
    private final int[] positions;
    private final int[] scores;

    /**
     * @param positions ship x coordinate at sample i (time i * 100 ms)
     * @param scores    score at sample i; same length as {@code positions}
     */
    public GhostTrack(final Mode mode, final String runId, final int finalScore,
            final int[] positions, final int[] scores) {
        if (mode == null)
            throw new IllegalArgumentException("mode must not be null.");
        if (runId == null || runId.trim().isEmpty())
            throw new IllegalArgumentException("runId must not be blank.");
        if (positions == null || scores == null || positions.length != scores.length)
            throw new IllegalArgumentException("positions and scores must have equal length.");
        if (positions.length > MAX_SAMPLES)
            throw new IllegalArgumentException("too many samples.");
        this.mode = mode;
        this.runId = runId;
        this.finalScore = finalScore;
        this.positions = positions.clone();
        this.scores = scores.clone();
    }

    public Mode getMode() { return this.mode; }
    public String getRunId() { return this.runId; }
    public int getFinalScore() { return this.finalScore; }
    public int getSampleCount() { return this.positions.length; }
    public int getPosition(final int index) { return this.positions[index]; }
    public int getScore(final int index) { return this.scores[index]; }

    /** Active duration covered by the samples. */
    public long getDurationMillis() {
        return this.positions.length == 0 ? 0L
                : (this.positions.length - 1) * SAMPLE_INTERVAL_MILLIS;
    }

    /** Index of the sample at or just before the given active time, or -1 if empty. */
    public int sampleIndexAt(final long activeMillis) {
        if (this.positions.length == 0)
            return -1;
        long index = Math.max(0L, activeMillis) / SAMPLE_INTERVAL_MILLIS;
        return (int) Math.min(index, this.positions.length - 1L);
    }

    /** True once the replayed run has ended at this active time (show the end marker). */
    public boolean isFinishedAt(final long activeMillis) {
        return activeMillis > getDurationMillis();
    }

    /** Score the ghost had at this active time; its final score after it finished. */
    public int scoreAt(final long activeMillis) {
        if (this.positions.length == 0 || isFinishedAt(activeMillis))
            return this.finalScore;
        return this.scores[sampleIndexAt(activeMillis)];
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof GhostTrack))
            return false;
        GhostTrack that = (GhostTrack) other;
        return this.mode == that.mode && this.runId.equals(that.runId)
                && this.finalScore == that.finalScore
                && Arrays.equals(this.positions, that.positions)
                && Arrays.equals(this.scores, that.scores);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * this.runId.hashCode() + this.mode.hashCode())
                + Arrays.hashCode(this.positions);
    }
}
