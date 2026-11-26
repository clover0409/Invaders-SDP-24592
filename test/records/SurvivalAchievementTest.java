package records;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurvivalAchievementTest {
    @TempDir Path directory;

    @Test
    void ordinarySixtySecondRunDoesNotClearTheChallenge() {
        RecordsService service = new RecordsService(directory.resolve("profile.properties"));
        try {
            service.startRun("ordinary");
            service.activeDuration("ordinary", 60_000);
            service.completeRun(new RunRecord("ordinary", 100, 0, 60_000, 1));
            assertNotNull(service.getUnlockTime(Achievement.SIXTY_SECONDS));
            assertNull(service.getUnlockTime(Achievement.SURVIVAL_CHALLENGE));
        } finally {
            service.close();
        }
    }

    @Test
    void challengeUnlockDoesNotLeakIntoTheNextRunsNotifications() {
        RecordsService service = new RecordsService(directory.resolve("profile.properties"));
        try {
            assertTrue(service.completeSurvivalChallenge());
            assertFalse(service.completeSurvivalChallenge());
            assertTrue(service.getCurrentRunUnlocks().isEmpty());
            assertNull(service.pollNotification());
            assertTrue(service.getTopRuns().isEmpty());
        } finally {
            service.close();
        }
        assertFalse(service.completeSurvivalChallenge());
    }
}
