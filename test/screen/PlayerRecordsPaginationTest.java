package screen;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import engine.Core;
import engine.DrawManager;
import records.RecordsService;

class PlayerRecordsPaginationTest {
    @TempDir Path directory;

    @Test
    void pagesWrapAndTabSwitchResetsThePageWithoutLeavingTheScreen() throws Exception {
        RecordsService records = new RecordsService(directory.resolve("profile.properties"));
        try {
            PlayerRecordsScreen screen = new PlayerRecordsScreen(432, 490, 60, records);
            screen.inputDelay = Core.getCooldown(0);
            screen.isRunning = true;
            press(screen, KeyEvent.VK_DOWN);
            assertEquals(0, value(screen, "achievementPage"));
            press(screen, KeyEvent.VK_RIGHT);
            assertEquals(1, value(screen, "tab"));
            press(screen, KeyEvent.VK_UP);
            assertEquals(DrawManager.getAchievementPageCount() - 1, value(screen, "achievementPage"));
            press(screen, KeyEvent.VK_DOWN);
            assertEquals(0, value(screen, "achievementPage"));
            press(screen, KeyEvent.VK_DOWN);
            assertEquals(1, value(screen, "achievementPage"));
            press(screen, KeyEvent.VK_LEFT);
            assertEquals(0, value(screen, "tab"));
            assertEquals(0, value(screen, "achievementPage"));
            assertTrue(screen.isRunning);
            press(screen, KeyEvent.VK_ESCAPE);
            assertFalse(screen.isRunning);
            assertEquals(1, screen.returnCode);
        } finally {
            records.close();
        }
    }

    private static int value(final PlayerRecordsScreen screen, final String name) throws Exception {
        Field field = PlayerRecordsScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(screen);
    }

    private static void press(final PlayerRecordsScreen screen, final int key) throws Exception {
        Field field = PlayerRecordsScreen.class.getDeclaredField("nextInputAt");
        field.setAccessible(true);
        field.setLong(screen, 0);
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
}
