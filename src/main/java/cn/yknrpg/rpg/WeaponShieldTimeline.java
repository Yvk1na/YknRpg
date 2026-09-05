package cn.yknrpg.rpg;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class WeaponShieldTimeline {
    private static final double EPSILON = 0.000001;

    private final Map<String, ShieldOffer> offers;
    private String activeSource;
    private long sequence;

    WeaponShieldTimeline() {
        offers = new HashMap<>();
    }

    private WeaponShieldTimeline(WeaponShieldTimeline source) {
        offers = new HashMap<>();
        source.offers.forEach((key, offer) -> offers.put(key, offer.copy()));
        activeSource = source.activeSource;
        sequence = source.sequence;
    }

    WeaponShieldTimeline copy() {
        return new WeaponShieldTimeline(this);
    }

    void grant(String source, double value, long expiresAt, long now) {
        refresh(now);
        ShieldOffer active = activeOffer();
        ShieldOffer replacement = new ShieldOffer(source, value, value, expiresAt, ++sequence);
        offers.put(source, replacement);

        if (active == null || source.equals(activeSource)) {
            activeSource = source;
            return;
        }
        if (value >= active.capacity) {
            // An equal or stronger shield replaces the current one. Only weaker later
            // releases wait for inheritance; the replaced shield does not return.
            offers.remove(activeSource);
            activeSource = source;
        }
    }

    double damage(double amount) {
        ShieldOffer active = activeOffer();
        if (active == null || active.remaining <= EPSILON || amount <= 0.0) return 0.0;
        double consumed = Math.min(active.remaining, amount);
        active.remaining -= consumed;
        if (active.remaining <= EPSILON) active.remaining = 0.0;
        return consumed;
    }

    void refresh(long now) {
        Iterator<Map.Entry<String, ShieldOffer>> iterator = offers.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt <= now) iterator.remove();
        }
        if (activeSource != null && offers.containsKey(activeSource)) return;

        ShieldOffer best = null;
        for (ShieldOffer candidate : offers.values()) {
            if (best == null
                    || candidate.capacity > best.capacity + EPSILON
                    || (Math.abs(candidate.capacity - best.capacity) <= EPSILON
                    && candidate.sequence > best.sequence)) {
                best = candidate;
            }
        }
        activeSource = best == null ? null : best.source;
    }

    String activeSource() {
        return activeSource;
    }

    double activeCapacity() {
        ShieldOffer active = activeOffer();
        return active == null ? 0.0 : active.capacity;
    }

    double activeRemaining() {
        ShieldOffer active = activeOffer();
        return active == null ? 0.0 : active.remaining;
    }

    long activeExpiresAt() {
        ShieldOffer active = activeOffer();
        return active == null ? 0L : active.expiresAt;
    }

    int size() {
        return offers.size();
    }

    boolean isEmpty() {
        return offers.isEmpty();
    }

    private ShieldOffer activeOffer() {
        return activeSource == null ? null : offers.get(activeSource);
    }

    private static final class ShieldOffer {
        private final String source;
        private final double capacity;
        private double remaining;
        private final long expiresAt;
        private final long sequence;

        private ShieldOffer(String source, double capacity, double remaining,
                            long expiresAt, long sequence) {
            this.source = source;
            this.capacity = capacity;
            this.remaining = remaining;
            this.expiresAt = expiresAt;
            this.sequence = sequence;
        }

        private ShieldOffer copy() {
            return new ShieldOffer(source, capacity, remaining, expiresAt, sequence);
        }
    }
}
