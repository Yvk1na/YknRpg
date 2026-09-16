package cn.yknrpg.gui;

import cn.yknrpg.item.ItemStatOverrides.EditableStat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemEditGuiTest {
    @Test
    void tenOrderedButtonsLeaveOneItemSlotAboveThem() {
        assertEquals(List.of("伤害", "力量", "暴击率", "暴击伤害", "智慧", "防御", "攻击速度", "技能伤害", "治疗增幅", "速度"),
                Arrays.stream(EditableStat.values()).map(EditableStat::label).toList());
        assertEquals(10, ItemEditGui.BUTTON_SLOTS.size());
        assertEquals(10, new HashSet<>(ItemEditGui.BUTTON_SLOTS).size());
        assertTrue(ItemEditGui.BUTTON_SLOTS.stream().allMatch(slot ->
                slot / 9 > ItemEditGui.ITEM_SLOT / 9 && slot < ItemEditGui.SIZE));
        assertEquals(List.of("暴击率", "暴击伤害", "攻击速度", "技能伤害", "治疗增幅"),
                Arrays.stream(EditableStat.values()).filter(EditableStat::percent).map(EditableStat::label).toList());
    }

    @Test
    void cancelledDeathNeverMovesTheEditingItemToDropsThatWillNotSpawn() throws Exception {
        EventHandler handler = ItemEditGui.class.getMethod("onDeath", PlayerDeathEvent.class)
                .getAnnotation(EventHandler.class);
        assertTrue(handler.ignoreCancelled());
    }
}
