package cn.yknitems.model;

import java.util.Map;

public record ArmorSetDefinition(
        String id,
        String name,
        Stage stage,
        int requiredLevel,
        Map<ArmorSlot, String> pieces,
        double outgoingDamageMultiplier,
        double incomingDamageMultiplier,
        double staffDamageMultiplier,
        double staffCooldownMultiplier,
        String bonusDescription
) {
}
