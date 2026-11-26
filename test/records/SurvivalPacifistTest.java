package records;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurvivalPacifistTest {
    @TempDir Path directory;

    @Test
    void onlyAZeroShotClearUnlocksAndPersistsTheTitle() throws Exception {
        Path profile = directory.resolve("profile.properties");
        RecordsService records = new RecordsService(profile);
        Long unlockedAt;
        try {
            assertFalse(records.awardSurvivalPacifist(false, 0));
            assertFalse(records.awardSurvivalPacifist(true, 1));
            assertNull(records.getUnlockTime(Achievement.SURVIVAL_PACIFIST));
            assertTrue(records.awardSurvivalPacifist(true, 0));
            unlockedAt = records.getUnlockTime(Achievement.SURVIVAL_PACIFIST);
            assertNotNull(unlockedAt);
            assertFalse(records.awardSurvivalPacifist(true, 0));
            assertEquals(0, records.getRecordCount());
            assertTrue(records.consumeSurvivalPacifistReveal());
            assertFalse(records.consumeSurvivalPacifistReveal());
            assertNull(records.pollNotification());
        } finally {
            records.close();
        }
        assertTrue(Files.readString(profile).contains("achievement.count=0"),
                "Older readers should still see the standard achievement list");
        RecordsService reloaded = new RecordsService(profile);
        try {
            assertEquals(unlockedAt, reloaded.getUnlockTime(Achievement.SURVIVAL_PACIFIST));
            assertFalse(reloaded.awardSurvivalPacifist(true, 0));
            assertFalse(reloaded.consumeSurvivalPacifistReveal());
        } finally {
            reloaded.close();
        }
    }
}
