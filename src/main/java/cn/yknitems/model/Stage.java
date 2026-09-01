package cn.yknitems.model;

public enum Stage {
    EARLY("early"),
    MID("mid"),
    LATE("late");

    private final String key;

    Stage(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Stage fromKey(String value) {
        for (Stage stage : values()) {
            if (stage.key.equalsIgnoreCase(value)) return stage;
        }
        throw new IllegalArgumentException("未知装备阶段：" + value);
    }
}
