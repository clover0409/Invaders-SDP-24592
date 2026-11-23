package records;

import java.util.OptionalInt;
import java.util.OptionalLong;

/** Compares a completed run with the personal best before it was recorded. */
public record PersonalBestComparison(int score, OptionalInt previousBest) {
    /** Returns current score minus previous best, or empty on the first run. */
    public OptionalLong differenceFromPreviousBest() {
        return previousBest.isPresent()
                ? OptionalLong.of((long) score - previousBest.getAsInt())
                : OptionalLong.empty();
    }
}
