package records;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CompletionistAchievementTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(value = Achievement.class, names = "ALL_ACHIEVEMENTS", mode = EnumSource.Mode.EXCLUDE)
    void everyPrerequisiteCanBeLastAndUnlocksOnlyOnce(final Achievement last) throws Exception {
        Path file = directory.resolve("profile.properties");
        Map<String, Long> earned = prerequisitesExcept(last);
        List<RunRecord> history = last == Achievement.FIRST_RUN ? List.of()
                : List.of(new RunRecord("previous", 10, 0, 0, 100));
        new ProfileStore(file).write(new ProfileSnapshot(history, earned));
        RecordsService service = new RecordsService(file);
        Long completionTime;
        try {
            assertNull(service.getUnlockTime(Achievement.ALL_ACHIEVEMENTS));
            List<Achievement> challengeUnlocks = earn(service, last);
            completionTime = service.getUnlockTime(Achievement.ALL_ACHIEVEMENTS);
            assertNotNull(completionTime);
            assertEquals(service.getUnlockTime(last), completionTime);
            if (last == Achievement.SURVIVAL_CHALLENGE) {
                assertEquals(List.of(last, Achievement.ALL_ACHIEVEMENTS), challengeUnlocks);
                assertTrue(service.getCurrentRunUnlocks().isEmpty());
                assertNull(service.pollNotification());
            } else {
                assertEquals(List.of(last, Achievement.ALL_ACHIEVEMENTS), service.getCurrentRunUnlocks());
                assertEquals(last, service.pollNotification());
                assertEquals(Achievement.ALL_ACHIEVEMENTS, service.pollNotification());
                assertNull(service.pollNotification());
            }
            earn(service, last);
            assertEquals(completionTime, service.getUnlockTime(Achievement.ALL_ACHIEVEMENTS));
            assertNull(service.pollNotification());
        } finally {
            service.close();
        }
        RecordsService reloaded = new RecordsService(file);
        try {
            assertEquals(completionTime, reloaded.getUnlockTime(Achievement.ALL_ACHIEVEMENTS));
            assertNull(reloaded.pollNotification());
        } finally {
            reloaded.close();
        }
    }

    @Test
    void backfillsOlderCompleteProfilesWithoutReplayingUnlocks() throws Exception {
        Path file = directory.resolve("old-profile.properties");
        Map<String, Long> earned = prerequisitesExcept(null);
        earned.put(Achievement.SURVIVAL_CHALLENGE.getId(), 2000L);
        RunRecord run = new RunRecord("previous", 42, 20, 60_000, 1000);
        new ProfileStore(file).write(new ProfileSnapshot(List.of(run), earned));
        RecordsService service = new RecordsService(file);
        try {
            assertEquals(Long.valueOf(2000), service.getUnlockTime(Achievement.ALL_ACHIEVEMENTS));
            assertEquals(1, service.getRecordCount());
            assertEquals(42, service.getHighestScore());
            assertTrue(service.getCurrentRunUnlocks().isEmpty());
            assertNull(service.pollNotification());
        } finally {
            service.close();
        }
        ProfileSnapshot saved = new ProfileStore(file).load();
        assertEquals(Achievement.values().length, saved.getUnlockTimes().size());
        assertEquals(Long.valueOf(2000), saved.getUnlockTimes().get("all_achievements"));
        assertEquals("previous", saved.getRecords().get(0).getRunId());
        for (Map.Entry<String, Long> entry : earned.entrySet())
            assertEquals(entry.getValue(), saved.getUnlockTimes().get(entry.getKey()));
    }

    @Test
    void emptyProfileDoesNotUnlockCompletionist() {
        RecordsService service = new RecordsService(directory.resolve("empty.properties"));
        try {
            assertNull(service.getUnlockTime(Achievement.ALL_ACHIEVEMENTS));
            assertNull(service.pollNotification());
        } finally {
            service.close();
        }
    }

    private static Map<String, Long> prerequisitesExcept(final Achievement missing) {
        Map<String, Long> earned = new LinkedHashMap<>();
        for (Achievement achievement : Achievement.values()) {
            if (achievement != Achievement.ALL_ACHIEVEMENTS && achievement != missing)
                earned.put(achievement.getId(), 1000L);
        }
        return earned;
    }

    private static List<Achievement> earn(final RecordsService service, final Achievement achievement) {
        service.startRun("final");
        switch (achievement) {
            case FIRST_ENEMY -> service.enemyDefeated("final", "enemy");
            case TWENTY_ENEMIES -> {
                for (int i = 0; i < 20; i++) service.enemyDefeated("final", "enemy-" + i);
            }
            case SIXTY_SECONDS -> service.activeDuration("final", 60_000);
            case FIRST_RUN, BEAT_HIGH_SCORE -> service.completeRun(new RunRecord("final", 20, 0, 0, 2000));
            case SURVIVAL_CHALLENGE -> { return service.completeSurvivalChallengeWithUnlocks(); }
            default -> throw new AssertionError("Not a prerequisite: " + achievement);
        }
        return List.of();
    }
}
