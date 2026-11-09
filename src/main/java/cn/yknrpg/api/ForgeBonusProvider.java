package cn.yknrpg.api;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import java.util.List;

/** Optional Bukkit service supplied by a forging plugin. */
public interface ForgeBonusProvider {
    /** 返回锻造倍率；只乘物品已有属性，不受影响的属性返回 1.0。 */
    double multiplier(ItemStack stack, String statId);

    /** 碎裂等状态使该物品的全部属性与技能失效。 */
    boolean disabled(ItemStack stack);

    /** 追加到 Lore 末尾的行，无附加行时返回空列表。 */
    List<Component> loreLines(ItemStack stack);
}
