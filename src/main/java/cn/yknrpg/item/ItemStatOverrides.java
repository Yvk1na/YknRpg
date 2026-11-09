package cn.yknrpg.item;

import cn.yknrpg.rpg.RpgRegistry.Stat;
import cn.yknrpg.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Absolute RPG stat overrides stored on this item, never on its MythicMobs template. */
public final class ItemStatOverrides {
    public static final double MAX_VALUE = 1_000_000;
    private static final Pattern INPUT = Pattern.compile("\\+?(?:\\d+(?:\\.\\d*)?|\\.\\d+)\\s*%?");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\s*(伤害|力量|暴击率|暴击伤害|智慧|智力|防御|攻击速度|技能伤害|治疗增幅|速度|生命|生命值|活力|凶猛)"
                    + "\\s*[:：]\\s*[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)\\s*%?(?:\\s*\\(\\+[^)]*\\))?\\s*");
    private static final Pattern SEPARATOR = Pattern.compile("[|｜]");
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private ItemStatOverrides() {}

    public enum EditableStat {
        DAMAGE(Stat.DAMAGE, "伤害", false, "&c"),
        STRENGTH(Stat.STRENGTH, "力量", false, "&c"),
        CRIT_CHANCE(Stat.CRIT_CHANCE, "暴击率", true, "&9"),
        CRIT_DAMAGE(Stat.CRIT_DAMAGE, "暴击伤害", true, "&9"),
        WISDOM(Stat.WISDOM, "智慧", false, "&b"),
        DEFENSE(Stat.DEFENSE, "防御", false, "&a"),
        ATTACK_SPEED(Stat.ATTACK_SPEED, "攻击速度", true, "&e"),
        ABILITY_DAMAGE(Stat.ABILITY_DAMAGE, "技能伤害", true, "&d"),
        MENDING(Stat.MENDING, "治疗增幅", true, "&a"),
        SPEED(Stat.SPEED, "速度", false, "&f");

        private final Stat stat;
        private final String label;
        private final boolean percent;
        private final String color;
        private final NamespacedKey key;

        EditableStat(Stat stat, String label, boolean percent, String color) {
            this.stat = stat;
            this.label = label;
            this.percent = percent;
            this.color = color;
            this.key = new NamespacedKey("yknrpg", "stat_" + stat.name().toLowerCase(Locale.ROOT));
        }

        public Stat stat() { return stat; }
        public String label() { return label; }
        public boolean percent() { return percent; }

        private boolean matches(String value) {
            return label.equals(value) || this == WISDOM && value.equals("智力");
        }
    }

    public static double parseValue(String text) {
        String input = text == null ? "" : text.trim();
        if (input.length() > 64 || !INPUT.matcher(input).matches()) {
            throw new IllegalArgumentException("请输入 0 到 1000000 之间的数字，可带小数或百分号");
        }
        double value = Double.parseDouble(input.replace("%", "").trim());
        checkValue(value);
        return value;
    }

    public static String formatValue(double value, boolean percent) {
        checkValue(value);
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() + (percent ? "%" : "");
    }

    public static void apply(ItemStack stack, EditableStat stat, double value) {
        checkValue(value);
        if (stack == null || stack.getType().isAir() || stack.getAmount() != 1 || stat == null) {
            throw new IllegalArgumentException("每次只能编辑一件非空物品");
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("该物品不支持属性编辑");
        meta.getPersistentDataContainer().set(stat.key, PersistentDataType.DOUBLE, value);
        meta.lore(rewriteLore(meta.lore(), stat, value));
        stack.setItemMeta(meta);
    }

    public static Map<Stat, Double> read(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return Map.of();
        var data = stack.getItemMeta().getPersistentDataContainer();
        Map<Stat, Double> values = new EnumMap<>(Stat.class);
        for (EditableStat stat : EditableStat.values()) {
            Double value = data.get(stat.key, PersistentDataType.DOUBLE);
            if (value != null && Double.isFinite(value) && value >= 0 && value <= MAX_VALUE) {
                values.put(stat.stat, value);
            }
        }
        return values;
    }

    public static boolean hasOverrides(ItemStack stack) {
        return !read(stack).isEmpty();
    }

    public static void stripRetiredLore(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return;
        ItemMeta meta = stack.getItemMeta();
        List<Component> oldLore = meta.lore();
        if (oldLore == null) return;
        List<Component> updated = rewriteLore(oldLore, null, 0);
        if (!updated.equals(oldLore)) {
            meta.lore(updated);
            stack.setItemMeta(meta);
        }
    }

    private static void checkValue(double value) {
        if (!Double.isFinite(value) || value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException("属性必须是 0 到 1000000 之间的有限数字");
        }
    }

    static List<Component> rewriteLore(List<Component> lore, EditableStat stat, double value) {
        List<Component> result = new ArrayList<>();
        boolean replaced = false;
        int insertAt = 0;
        for (Component line : lore == null ? List.<Component>of() : lore) {
            String plain = PLAIN.serialize(line);
            List<Component> kept = new ArrayList<>();
            Matcher separators = SEPARATOR.matcher(plain);
            boolean changed = false;
            boolean attributeLine = false;
            int start = 0;
            do {
                boolean found = separators.find();
                int end = found ? separators.start() : plain.length();
                Matcher attribute = ATTRIBUTE.matcher(plain.substring(start, end));
                if (attribute.matches()) {
                    attributeLine = true;
                    String label = attribute.group(1);
                    if (label.equals("活力") || label.equals("凶猛")) {
                        changed = true;
                    } else if (stat != null && stat.matches(label)) {
                        kept.add(statLine(stat, value));
                        replaced = true;
                        changed = true;
                    } else {
                        kept.add(sliceTrimmed(line, plain, start, end));
                    }
                } else {
                    kept.add(sliceTrimmed(line, plain, start, end));
                }
                if (!found) break;
                start = separators.end();
            } while (true);
            if (!changed) result.add(line);
            else if (!kept.isEmpty()) {
                Component joined = kept.getFirst();
                for (int index = 1; index < kept.size(); index++) {
                    joined = Component.empty().append(joined)
                            .append(Component.text(" | ", NamedTextColor.GRAY)).append(kept.get(index));
                }
                result.add(joined);
            }
            if (attributeLine) insertAt = result.size();
        }
        if (stat != null && !replaced) result.add(insertAt, statLine(stat, value));
        return result;
    }

    private static Component statLine(EditableStat stat, double value) {
        return Text.component("&7" + stat.label + "：" + stat.color + "+" + formatValue(value, stat.percent))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component sliceTrimmed(Component line, String plain, int start, int end) {
        while (start < end && Character.isWhitespace(plain.charAt(start))) start++;
        while (end > start && Character.isWhitespace(plain.charAt(end - 1))) end--;
        return slice(line, start, end);
    }

    /** Keep styles and events on unaffected parts, including separately colored numbers. */
    static Component slice(Component component, int start, int end) {
        String plain = PLAIN.serialize(component);
        if (start == 0 && end == plain.length()) return component;
        if (!(component instanceof TextComponent text)) {
            return Component.text(plain.substring(start, end)).style(component.style());
        }
        int ownLength = text.content().length();
        Component result = text.content(text.content().substring(Math.min(start, ownLength),
                Math.min(end, ownLength))).children(List.of());
        int offset = ownLength;
        for (Component child : component.children()) {
            int length = PLAIN.serialize(child).length();
            if (end > offset && start < offset + length) {
                result = result.append(slice(child, Math.max(0, start - offset), Math.min(length, end - offset)));
            }
            offset += length;
        }
        return result;
    }
}
