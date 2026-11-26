package screen;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import audio.AudioManager;
import records.Achievement;

/** Shows the final status of a completed 60-second survival attempt. */
public final class SurvivalResultScreen extends Screen {
    private final int score;
    private final long survivedMillis;
    private final boolean survived;
    private final List<Achievement> achievements;
    private boolean spaceWasDown;
    private boolean escapeWasDown;

    public SurvivalResultScreen(final int width, final int height, final int fps,
            final int score, final long survivedMillis, final boolean survived) {
        this(width, height, fps, score, survivedMillis, survived, Collections.emptyList());
    }

    public SurvivalResultScreen(final int width, final int height, final int fps,
            final int score, final long survivedMillis, final boolean survived,
            final List<Achievement> achievements) {
        super(width, height, fps);
        this.score = score;
        this.survivedMillis = survivedMillis;
        this.survived = survived;
        this.achievements = survived
                ? Collections.unmodifiableList(new ArrayList<Achievement>(achievements))
                : Collections.emptyList();
        if (!this.achievements.isEmpty())
            AudioManager.playSFX("audio/sfx/achievement_unlock.wav");
        this.spaceWasDown = inputManager.isKeyDown(KeyEvent.VK_SPACE);
        this.escapeWasDown = inputManager.isKeyDown(KeyEvent.VK_ESCAPE);
        AudioManager.playBGM(survived
                ? "audio/bgm/music_end_victory.wav" : "audio/bgm/music_end_defeat.wav");
    }

    @Override
    public int run() {
        super.run();
        return this.returnCode;
    }

    @Override
    protected void update() {
        super.update();
        boolean acceptsInput = this.inputDelay.checkFinished();
        drawManager.initDrawing(this);
        drawManager.drawSurvivalResult(this, this.score, this.survivedMillis,
                this.survived, acceptsInput);
        drawManager.drawSurvivalAchievements(this, this.achievements);
        drawManager.completeDrawing(this);

        boolean spaceDown = inputManager.isKeyDown(KeyEvent.VK_SPACE);
        boolean escapeDown = inputManager.isKeyDown(KeyEvent.VK_ESCAPE);
        if (acceptsInput) {
            if (escapeDown && !this.escapeWasDown) {
                this.returnCode = 14;
                this.isRunning = false;
                AudioManager.playSFX("audio/sfx/menu_back.wav");
            } else if (spaceDown && !this.spaceWasDown) {
                this.returnCode = GameModeScreen.SURVIVAL_MODE;
                this.isRunning = false;
                AudioManager.playSFX("audio/sfx/menu_select.wav");
            }
        }
        this.spaceWasDown = spaceDown;
        this.escapeWasDown = escapeDown;
    }
}
