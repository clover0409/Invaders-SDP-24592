package screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import records.Achievement;
import records.RecordsService;
import records.RunRecord;

import engine.Core;
import engine.DrawManager.SpriteType;
import entity.Bullet;
import entity.BulletPool;
import entity.Ship;
import entity.ZigzagEnemyShip;

class SurvivalChallengeTest {
    private final AtomicLong clock = new AtomicLong();
    @TempDir Path directory;
    private RecordsService records;

    @BeforeEach
    void openRecords() {
        records = new RecordsService(directory.resolve("profile.properties"));
    }

    @AfterEach
    void closeRecords() {
        records.close();
    }

    @Test
    void startsAfterCountdownAndClearsAtSixtySeconds() {
        InfiniteGameScreen challenge = newChallenge();
        advance(10_000);
        challenge.update();
        assertEquals(0, challenge.getSurvivalElapsedMillis());
        assertEquals(1, challenge.getGameState().getLivesRemaining());
        assertNull(challenge.getActiveRunId());

        startGameplay(challenge);
        advance(59_999);
        challenge.update();
        assertFalse(challenge.hasSurvived());
        assertTrue(challenge.isRunning);
        assertEquals(59_999, challenge.getSurvivalElapsedMillis());
        assertNull(records.getUnlockTime(Achievement.SURVIVAL_CHALLENGE));

        advance(1);
        challenge.update();
        assertTrue(challenge.hasSurvived());
        assertFalse(challenge.isRunning);
        assertEquals(60_000, challenge.getSurvivalElapsedMillis());
        assertEquals(1, challenge.getGameState().getLivesRemaining());
        assertEquals(List.of(Achievement.SURVIVAL_CHALLENGE), challenge.getSurvivalUnlocks());
        assertEquals(0, records.getRecordCount());
        assertNull(records.getUnlockTime(Achievement.ALL_ACHIEVEMENTS));
    }

    @Test
    void crossingDeadlineAfterAStallStillUpdatesEnemiesBeforeClearing() throws Exception {
        InfiniteGameScreen challenge = newChallenge();
        startGameplay(challenge);
        advance(59_000);
        challenge.update();
        ZigzagEnemyShip enemy = new ZigzagEnemyShip(100, 60, SpriteType.EnemyShipA1, 2);
        enemies(challenge).add(enemy);
        int priorY = enemy.getPositionY();

        // No game updates for two seconds across the deadline.
        advance(2_000);
        challenge.update();

        assertTrue(enemy.getPositionY() > priorY, "The resumed frame must update enemies");
        assertEquals(60_000, challenge.getSurvivalElapsedMillis());
        assertTrue(challenge.hasSurvived());
        assertFalse(challenge.isRunning);
    }

    @Test
    void crossingDeadlineAfterAStallStillDetectsAFatalCollision() throws Exception {
        InfiniteGameScreen challenge = newChallenge();
        startGameplay(challenge);
        advance(59_000);
        challenge.update();
        queueHit(challenge);

        advance(2_000);
        challenge.update();

        assertEquals(0, challenge.getGameState().getLivesRemaining());
        assertEquals(60_000, challenge.getSurvivalElapsedMillis());
        assertFalse(challenge.hasSurvived(), "A fatal collision in the final frame takes precedence");
        assertTrue(challenge.getSurvivalUnlocks().isEmpty());
        assertNull(records.getUnlockTime(Achievement.SURVIVAL_CHALLENGE));
    }

    @Test
    void aFatalHitEndsTheChallengeAndFreezesTheTime() throws Exception {
        InfiniteGameScreen challenge = newChallenge();
        startGameplay(challenge);
        advance(29_500);
        queueHit(challenge);
        challenge.update();
        assertEquals(0, challenge.getGameState().getLivesRemaining());

        advance(40_000);
        challenge.update();
        assertEquals(29_500, challenge.getSurvivalElapsedMillis());
        assertFalse(challenge.hasSurvived());
        assertTrue(challenge.getSurvivalUnlocks().isEmpty());
        assertNull(records.getUnlockTime(Achievement.SURVIVAL_CHALLENGE));
    }

    @Test
    void escapeAbandonsDuringCountdownAndRetryStartsFresh() {
        InfiniteGameScreen challenge = newChallenge();
        press(challenge, KeyEvent.VK_ESCAPE);
        assertTrue(challenge.isAbandoned());
        assertFalse(challenge.isRunning);
        assertTrue(challenge.getSurvivalUnlocks().isEmpty());
        assertNull(records.getUnlockTime(Achievement.SURVIVAL_CHALLENGE));

        SurvivalResultScreen result = new SurvivalResultScreen(448, 520, 60, 0, 0, false);
        result.inputDelay = Core.getCooldown(0);
        result.isRunning = true;
        result.update();
        press(result, KeyEvent.VK_SPACE);
        assertEquals(GameModeScreen.SURVIVAL_MODE, result.returnCode);
        assertFalse(result.isRunning);

        InfiniteGameScreen retry = newChallenge();
        assertFalse(retry.isAbandoned());
        assertEquals(0, retry.getSurvivalElapsedMillis());
        assertEquals(1, retry.getGameState().getLivesRemaining());
    }

    @Test
    void heldEscapeDoesNotBackOutOfTheModeMenu() {
        InfiniteGameScreen challenge = newChallenge();
        GameModeScreen menu;
        key(challenge, KeyEvent.VK_ESCAPE, true);
        try {
            challenge.update();
            assertTrue(challenge.isAbandoned());
            menu = new GameModeScreen(448, 520, 60);
            menu.isRunning = true;
            menu.inputDelay = Core.getCooldown(Integer.MAX_VALUE);
            menu.inputDelay.reset();
            menu.update();
            menu.inputDelay = Core.getCooldown(0);
            menu.update();
            assertTrue(menu.isRunning, "The exit press is still held after the input delay");
        } finally {
            key(challenge, KeyEvent.VK_ESCAPE, false);
        }
        menu.update();
        assertTrue(menu.isRunning, "Releasing Escape must not navigate");
        press(menu, KeyEvent.VK_ESCAPE);
        assertFalse(menu.isRunning);
        assertEquals(1, menu.returnCode);
    }

    @Test
    void spawnIntervalShrinksDuringTheRun() {
        int initial = InfiniteGameScreen.survivalSpawnInterval(0);
        int middle = InfiniteGameScreen.survivalSpawnInterval(30_000);
        int end = InfiniteGameScreen.survivalSpawnInterval(60_000);
        assertTrue(initial > middle && middle > end && end > 0);
    }

    @Test
    void lastChallengeUnlocksBothAchievementsAndRetryDoesNotRepeatThem() {
        records.completeRun(new RunRecord("first", 10, 0, 0, 1000));
        records.completeRun(new RunRecord("second", 20, 20, 60_000, 2000));
        InfiniteGameScreen challenge = newChallenge();
        startGameplay(challenge);
        advance(60_000);
        challenge.update();
        assertEquals(List.of(Achievement.SURVIVAL_CHALLENGE, Achievement.ALL_ACHIEVEMENTS),
                challenge.getSurvivalUnlocks());
        SurvivalResultScreen result = new SurvivalResultScreen(432, 490, 60, 0, 60_000, true,
                challenge.getSurvivalUnlocks());
        result.update();
        InfiniteGameScreen retry = newChallenge();
        startGameplay(retry);
        advance(60_000);
        retry.update();
        assertTrue(retry.hasSurvived());
        assertTrue(retry.getSurvivalUnlocks().isEmpty());
        assertEquals(2, records.getRecordCount());
    }

    private InfiniteGameScreen newChallenge() {
        InfiniteGameScreen challenge = InfiniteGameScreen.forSurvival(448, 520, 60, clock::get, records);
        challenge.initialize();
        challenge.inputDelay = Core.getCooldown(Integer.MAX_VALUE);
        challenge.inputDelay.reset();
        challenge.isRunning = true;
        return challenge;
    }

    private static void startGameplay(final InfiniteGameScreen challenge) {
        challenge.inputDelay = Core.getCooldown(0);
        challenge.update();
    }

    private void advance(final long millis) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private static void press(final Screen screen, final int key) {
        key(screen, key, true);
        try {
            screen.update();
        } finally {
            key(screen, key, false);
        }
    }

    private static void key(final Screen screen, final int key, final boolean pressed) {
        KeyEvent event = new KeyEvent(new Canvas(),
                pressed ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED,
                0, 0, key, KeyEvent.CHAR_UNDEFINED);
        if (pressed)
            screen.inputManager.keyPressed(event);
        else
            screen.inputManager.keyReleased(event);
    }

    @SuppressWarnings("unchecked")
    private static List<ZigzagEnemyShip> enemies(final InfiniteGameScreen challenge)
            throws ReflectiveOperationException {
        Field field = InfiniteGameScreen.class.getDeclaredField("enemies");
        field.setAccessible(true);
        return (List<ZigzagEnemyShip>) field.get(challenge);
    }

    @SuppressWarnings("unchecked")
    private static void queueHit(final InfiniteGameScreen challenge) throws ReflectiveOperationException {
        Field shipField = InfiniteGameScreen.class.getDeclaredField("ship");
        shipField.setAccessible(true);
        Ship ship = (Ship) shipField.get(challenge);
        Field bulletsField = InfiniteGameScreen.class.getDeclaredField("enemyBullets");
        bulletsField.setAccessible(true);
        Set<Bullet> bullets = (Set<Bullet>) bulletsField.get(challenge);
        bullets.add(BulletPool.getBullet(ship.getPositionX() + ship.getWidth() / 2,
                ship.getPositionY(), 4));
    }
}
