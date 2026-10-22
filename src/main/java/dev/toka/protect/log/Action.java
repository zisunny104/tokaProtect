package dev.toka.protect.log;

/** What happened to a block. The code is what is stored, so existing numbers must never change meaning. */
public enum Action {
    PLACE(0), BREAK(1), FILL(2), EMPTY(3), IGNITE(4), EXPLODE(5);

    private final int code;

    Action(int code) { this.code = code; }

    public int code() { return code; }

    public String langKey() { return "log.action." + name().toLowerCase(java.util.Locale.ROOT); }

    public static Action byCode(int code) {
        for (Action action : values()) if (action.code == code) return action;
        throw new IllegalArgumentException("Unknown action code " + code);
    }
}
