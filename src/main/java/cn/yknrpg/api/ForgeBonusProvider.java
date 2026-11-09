package cn.yknrpg.api;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import java.util.List;

/** Optional Bukkit service supplied by a forging plugin. */
public interface ForgeBonusProvider {
    /** 返回锻造倍率；只乘物品已有属性，不受影响的属性返回 1.0。 */
    double multiplier(ItemStack stack, String statId);

    /** 倍率之后追加的固定属性值；允许加入模板原本没有的属性。 */
    default double flat(ItemStack stack, String statId) {
        return 0;
    }

    /** 输入始终为未装饰的基础名，返回展示名。 */
    default Component decorateName(ItemStack stack, Component baseName) {
        return baseName;
    }

    /** 碎裂等状态使该物品的全部属性与技能失效。 */
    boolean disabled(ItemStack stack);

    /** 追加到 Lore 末尾的行，无附加行时返回空列表。 */
    List<Component> loreLines(ItemStack stack);
}
