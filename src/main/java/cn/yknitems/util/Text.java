package cn.yknitems.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Locale;

public final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static Component component(String value) {
        return LEGACY.deserialize(value == null ? "" : value);
    }

    public static String strip(String value) {
        return LEGACY.serialize(LEGACY.deserialize(value == null ? "" : value))
                .replaceAll("(?i)&[0-9A-FK-ORX]", "");
    }

    public static String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.00001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
