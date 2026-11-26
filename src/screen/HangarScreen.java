package screen;

import java.awt.event.KeyEvent;

import audio.AudioManager;
import engine.Core;
import engine.Cooldown;
import entity.Ship;
import entity.ShipType;

/** Lets the player customize the ship's color. */
public class HangarScreen extends Screen {

    private static final int SELECTION_TIME = 200;

    private final Cooldown selectionCooldown;
    private final Ship previewShip;
    private final boolean fromShop;

    public HangarScreen(final int width, final int height, final int fps) {
        this(width, height, fps, ShipType.STANDARD, false);
    }

    public HangarScreen(final int width, final int height, final int fps,
            final ShipType shipType, final boolean fromShop) {
        super(width, height, fps);
        this.fromShop = fromShop;

        this.returnCode = 1;
        this.selectionCooldown = Core.getCooldown(SELECTION_TIME);
        this.selectionCooldown.reset();
        this.previewShip = new Ship(width / 2 - 13, height / 2 - 8, shipType);
    }

    @Override
    public final int run() {
        super.run();
        return this.returnCode;
    }

    @Override
    protected void update() {
        super.update();

        if (this.inputDelay.checkFinished()
                && this.selectionCooldown.checkFinished()) {
            if (this.inputManager.isKeyDown(KeyEvent.VK_LEFT)
                    || this.inputManager.isKeyDown(KeyEvent.VK_A)) {
                ShipCustomization.changeColor(-1);
                this.selectionCooldown.reset();
                AudioManager.playSFX("audio/sfx/menu_move.wav");
            } else if (this.inputManager.isKeyDown(KeyEvent.VK_RIGHT)
                    || this.inputManager.isKeyDown(KeyEvent.VK_D)) {
                ShipCustomization.changeColor(1);
                this.selectionCooldown.reset();
                AudioManager.playSFX("audio/sfx/menu_move.wav");
            } else if (this.inputManager.isKeyDown(KeyEvent.VK_ESCAPE)) {
                this.returnCode = 1;
                this.isRunning = false;
                AudioManager.playSFX("audio/sfx/menu_back.wav");
            }
        }

        draw();
    }

    private void draw() {
        this.drawManager.initDrawing(this);

        this.drawManager.drawCenteredBigString(this, "Hangar",
                this.getHeight() / 5);

        this.previewShip.setColor(ShipCustomization.getColor());
        this.drawManager.drawEntity(this.previewShip,
                this.previewShip.getPositionX(),
                this.previewShip.getPositionY());

        this.drawManager.drawCenteredRegularString(this,
                "Color: " + ShipCustomization.getColorName(),
                this.getHeight() / 2 + 45);
        if (this.fromShop)
            this.drawManager.drawCosmeticPurchasePraise(this, this.getHeight() / 2 + 83);
        this.drawManager.drawCenteredRegularString(this,
                "Left/Right: change color",
                this.getHeight() - 60);
        this.drawManager.drawCenteredRegularString(this,
                this.fromShop ? "Esc: return to Shop" : "Esc: return to menu",
                this.getHeight() - 35);

        this.drawManager.completeDrawing(this);
    }
}
