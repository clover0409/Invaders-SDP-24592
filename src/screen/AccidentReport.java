package screen;

/** Flavor text for the hit that cost the player their last life. */
public enum AccidentReport {
    ENEMY_COLLISION("双方对路权存在分歧", "Both pilots disagreed on who had right of way."),
    ENEMY_PROJECTILE("飞船成功拦截一枚敌方子弹", "The ship successfully intercepted an enemy bullet.");

    private final String message;
    private final String fallback;

    AccidentReport(final String message, final String fallback) {
        this.message = message;
        this.fallback = fallback;
    }

    public String getMessage() { return this.message; }
    public String getFallback() { return this.fallback; }
}
