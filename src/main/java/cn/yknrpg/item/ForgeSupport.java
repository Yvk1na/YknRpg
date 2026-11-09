package cn.yknrpg.item;

import cn.yknrpg.api.ForgeBonusProvider;
import cn.yknrpg.rpg.RpgRegistry.Stat;
import cn.yknrpg.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

public final class ForgeSupport {
    private static final NamespacedKey LORE = new NamespacedKey("yknrpg", "forge_lore");
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private ForgeSupport() {}

    public static ForgeBonusProvider provider() {
        return Bukkit.getServer() == null
                ? null
                : Bukkit.getServicesManager().load(ForgeBonusProvider.class);
    }

    public static boolean disabled(ItemStack stack) {
        var provider = provider();
        return stack != null
                && !stack.getType().isAir()
                && provider != null
                && provider.disabled(stack);
    }

    public static double multiplier(ForgeBonusProvider provider, ItemStack stack, Stat stat) {
        if (provider == null) {
            return 1;
        }
        double value = provider.multiplier(stack, stat.name().toLowerCase(Locale.ROOT));
        return Double.isFinite(value) && value >= 0 ? value : 1;
    }

    public static boolean needsRewrite(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (stack.getPersistentDataContainer().has(LORE, PersistentDataType.STRING)) {
            return true;
        }
        var provider = provider();
        return provider != null && !provider.loreLines(stack).isEmpty();
    }

    public static void rewrite(ItemStack stack, Map<Stat, Double> base) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return;
        }
        var provider = provider();
        String previous = stack.getPersistentDataContainer().get(LORE, PersistentDataType.STRING);
        List<Component> extra = provider == null ? List.of() : provider.loreLines(stack);
        if (previous == null && extra.isEmpty()) {
            return;
        }
        var meta = stack.getItemMeta();
        var data = meta.getPersistentDataContainer();
        List<Component> old = meta.lore() == null ? List.of() : meta.lore();
        List<Component> input = new ArrayList<>(old);
        // Track exact generated lines; other plugins may append their own lore after ours.
        if (previous != null && !previous.isEmpty()) {
            removeGenerated(input, Arrays.asList(previous.split("\n", -1)));
        }
        removeGenerated(input, extra.stream().map(PLAIN::serialize).toList());
        boolean disabled = provider != null && provider.disabled(stack);
        List<Component> updated =
                rewriteLines(
                        input,
                        base,
                        stat -> disabled ? 0 : multiplier(provider, stack, stat),
                        true);
        updated.addAll(extra);
        if (!updated.equals(old)) {
            meta.lore(updated);
        }
        String suffix = String.join("\n", extra.stream().map(PLAIN::serialize).toList());
        if (!Objects.equals(previous, suffix)) {
            data.set(LORE, PersistentDataType.STRING, suffix);
        }
        if (!updated.equals(old) || !Objects.equals(previous, suffix)) {
            stack.setItemMeta(meta);
        }
    }

    private static void removeGenerated(List<Component> input, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        for (int start = 0; start + lines.size() <= input.size(); ) {
            var group = input.subList(start, start + lines.size());
            if (group.stream().map(PLAIN::serialize).toList().equals(lines)) {
                group.clear();
            } else {
                start++;
            }
        }
    }

    public static List<Component> rewriteLines(
            List<Component> input,
            Map<Stat, Double> base,
            java.util.function.ToDoubleFunction<Stat> multiplier) {
        return rewriteLines(input, base, multiplier, false);
    }

    private static List<Component> rewriteLines(
            List<Component> input,
            Map<Stat, Double> base,
            java.util.function.ToDoubleFunction<Stat> multiplier,
            boolean forged) {
        List<Component> output = new ArrayList<>(input);
        for (var entry : base.entrySet()) {
            double factor = multiplier.applyAsDouble(entry.getKey());
            if (!forged && factor == 1) {
                continue;
            }
            var editable =
                    Arrays.stream(ItemStatOverrides.EditableStat.values())
                            .filter(s -> s.stat() == entry.getKey())
                            .findFirst()
                            .orElse(null);
            String label =
                    editable == null
                            ? (entry.getKey() == Stat.HEALTH ? "生命值" : null)
                            : editable.label();
            if (label == null) {
                continue;
            }
            boolean percent = editable != null && editable.percent();
            String labels =
                    label.equals("智慧") ? "(?:智慧|智力)" : label.equals("生命值") ? "(?:生命值|生命)" : label;
            Pattern pattern =
                    Pattern.compile(
                            "(?<![\\p{L}])"
                                    + labels
                                    + "\\s*[:：]\\s*[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:\\s*%)?(?:\\s*\\(\\+[^)]*\\))?");
            double value = entry.getValue() * factor;
            double difference = value - entry.getValue();
            Component replacement =
                    Text.component(
                                    "&7"
                                            + label
                                            + "：&f+"
                                            + format(value, percent)
                                            + (difference > 0.00000001
                                                    ? " &8(+" + format(difference, percent) + ")"
                                                    : ""))
                            .decoration(TextDecoration.ITALIC, false);
            for (int i = 0; i < output.size(); i++) {
                Component line = output.get(i);
                String plain = PLAIN.serialize(line);
                var matcher = pattern.matcher(plain);
                if (!matcher.find()) {
                    continue;
                }
                Component rewritten = Component.empty();
                int start = 0;
                do {
                    rewritten =
                            rewritten
                                    .append(ItemStatOverrides.slice(line, start, matcher.start()))
                                    .append(replacement);
                    start = matcher.end();
                } while (matcher.find());
                output.set(
                        i,
                        rewritten
                                .append(ItemStatOverrides.slice(line, start, plain.length()))
                                .compact());
            }
        }
        return output;
    }

    public static String format(double value, boolean percent) {
        return BigDecimal.valueOf(value)
                        .setScale(2, java.math.RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString()
                + (percent ? "%" : "");
    }
}
