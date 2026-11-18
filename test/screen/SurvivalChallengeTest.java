package screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import engine.Core;
import entity.Bullet;
import entity.BulletPool;
import entity.Ship;

class SurvivalChallengeTest {
    private final AtomicLong clock = new AtomicLong();

    @Test
    void startsAfterCountdownAndClearsAtSixtySeconds() throws Exception {
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

        queueHit(challenge);
        advance(1);
        challenge.update();
        assertTrue(challenge.hasSurvived());
        assertFalse(challenge.isRunning);
        assertEquals(60_000, challenge.getSurvivalElapsedMillis());
        assertEquals(1, challenge.getGameState().getLivesRemaining(),
                "A collision after the deadline must not cancel a clear");
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
    }

    @Test
    void escapeAbandonsDuringCountdownAndRetryStartsFresh() {
        InfiniteGameScreen challenge = newChallenge();
        press(challenge, KeyEvent.VK_ESCAPE);
        assertTrue(challenge.isAbandoned());
        assertFalse(challenge.isRunning);

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
    void spawnIntervalShrinksDuringTheRun() {
        int initial = InfiniteGameScreen.survivalSpawnInterval(0);
        int middle = InfiniteGameScreen.survivalSpawnInterval(30_000);
        int end = InfiniteGameScreen.survivalSpawnInterval(60_000);
        assertTrue(initial > middle && middle > end && end > 0);
    }

    private InfiniteGameScreen newChallenge() {
        InfiniteGameScreen challenge = InfiniteGameScreen.forSurvival(448, 520, 60, clock::get);
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
        Canvas source = new Canvas();
        screen.inputManager.keyPressed(new KeyEvent(source, KeyEvent.KEY_PRESSED,
                0, 0, key, KeyEvent.CHAR_UNDEFINED));
        try {
            screen.update();
        } finally {
            screen.inputManager.keyReleased(new KeyEvent(source, KeyEvent.KEY_RELEASED,
                    0, 0, key, KeyEvent.CHAR_UNDEFINED));
        }
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
