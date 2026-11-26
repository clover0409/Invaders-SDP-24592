package screen;

import java.awt.event.KeyEvent;
import audio.AudioManager;
import engine.Cooldown;
import engine.Core;

public class GameModeScreen extends Screen { // Implementation of the game mode selection screen.

    public static final int SURVIVAL_MODE = 15;
    private static final int SELECTION_TIME = 200;
    private static final int NUM_CHOICES = 4;

    private int selected;
    private Cooldown selectionCooldown;
    private boolean escapeWasDown;

    public GameModeScreen(final int width, final int height, final int fps) {
        super(width, height, fps);
        this.selected = 0; // Se positionne par défaut sur le premier mode
        this.returnCode = 1; // Par défaut : retour au menu principal
        this.selectionCooldown = Core.getCooldown(SELECTION_TIME);
        this.selectionCooldown.reset();
        this.escapeWasDown = inputManager.isKeyDown(KeyEvent.VK_ESCAPE);
    }

    public final int run() {
        super.run();
        return this.returnCode;
    }

    protected final void update() {
        super.update();

        draw();
        boolean escapeDown = inputManager.isKeyDown(KeyEvent.VK_ESCAPE);
        boolean escapePressed = escapeDown && !this.escapeWasDown;
        this.escapeWasDown = escapeDown;
        if (!this.inputDelay.checkFinished())
            return;

        if (escapePressed) {
            this.returnCode = 1;
            this.isRunning = false;
            AudioManager.playSFX("audio/sfx/menu_back.wav");
            return;
        }

        if (this.selectionCooldown.checkFinished()) {

            if (inputManager.isKeyDown(KeyEvent.VK_UP)
                    || inputManager.isKeyDown(KeyEvent.VK_W)) {
                this.selected = this.selected == 0 ? NUM_CHOICES - 1
                        : this.selected - 1;
                this.selectionCooldown.reset();
                AudioManager.playSFX("audio/sfx/menu_move.wav");   // 추가 (두 곳 모두)
            }
            if (inputManager.isKeyDown(KeyEvent.VK_DOWN)
                    || inputManager.isKeyDown(KeyEvent.VK_S)) {
                this.selected = (this.selected + 1) % NUM_CHOICES;
                this.selectionCooldown.reset();
                AudioManager.playSFX("audio/sfx/menu_move.wav");
            }

            if (inputManager.isKeyDown(KeyEvent.VK_SPACE)) {
                this.returnCode = switch (this.selected) {
                    case 0 -> 8;
                    case 1 -> 2;
                    case 2 -> SURVIVAL_MODE;
                    default -> 1;
                };
                this.isRunning = false;
                if(this.returnCode==1)
                    AudioManager.playSFX("audio/sfx/menu_back.wav");
                else
                    AudioManager.playSFX("audio/sfx/menu_select.wav");
            }
        }
    }

    public final int getSelectedGameMode() {
        return this.selected;
    }

    private void draw() {
        drawManager.initDrawing(this);
        drawManager.drawGameModeMenu(this, this.selected);
        drawManager.completeDrawing(this);
    }
}
