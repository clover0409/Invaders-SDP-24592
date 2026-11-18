package screen;

import java.awt.event.KeyEvent;

import audio.AudioManager;

/** Shows the final status of a completed 60-second survival attempt. */
public final class SurvivalResultScreen extends Screen {
    private final int score;
    private final long survivedMillis;
    private final boolean survived;
    private boolean spaceWasDown;
    private boolean escapeWasDown;

    public SurvivalResultScreen(final int width, final int height, final int fps,
            final int score, final long survivedMillis, final boolean survived) {
        super(width, height, fps);
        this.score = score;
        this.survivedMillis = survivedMillis;
        this.survived = survived;
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
