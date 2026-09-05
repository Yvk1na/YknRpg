package cn.yknrpg.rpg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeaponShieldTimelineTest {
    @Test
    void sameSourceRefreshesValueAndFullDurationWithoutStacking() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:a", 100.0, 10_000L, 0L);
        timeline.damage(40.0);

        timeline.grant("item:a", 120.0, 14_000L, 4_000L);

        assertEquals(120.0, timeline.activeRemaining());
        assertEquals(120.0, timeline.activeCapacity());
        assertEquals(14_000L, timeline.activeExpiresAt());
        assertEquals(1, timeline.size());
    }

    @Test
    void weakerShieldWaitsAndKeepsItsOriginalExpiryWhenInherited() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:strong", 120.0, 10_000L, 0L);
        timeline.grant("item:weak", 80.0, 13_000L, 3_000L);

        assertEquals("item:strong", timeline.activeSource());
        timeline.refresh(10_000L);

        assertEquals("item:weak", timeline.activeSource());
        assertEquals(80.0, timeline.activeRemaining());
        assertEquals(13_000L, timeline.activeExpiresAt());
        timeline.refresh(13_000L);
        assertNull(timeline.activeSource());
    }

    @Test
    void strongerShieldImmediatelyReplacesThePreviousShield() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:weak", 80.0, 20_000L, 0L);
        timeline.grant("item:strong", 140.0, 12_000L, 2_000L);

        assertEquals("item:strong", timeline.activeSource());
        assertEquals(1, timeline.size());
        timeline.refresh(12_000L);
        assertNull(timeline.activeSource());
    }

    @Test
    void damageDoesNotLetAWeakerCandidateTakePriorityEarly() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:strong", 120.0, 10_000L, 0L);
        timeline.grant("item:weak", 80.0, 13_000L, 3_000L);

        assertEquals(120.0, timeline.damage(120.0));
        assertEquals("item:strong", timeline.activeSource());
        assertEquals(0.0, timeline.activeRemaining());

        timeline.refresh(10_000L);
        assertEquals("item:weak", timeline.activeSource());
        assertEquals(80.0, timeline.activeRemaining());
    }

    @Test
    void expiredCandidateCannotBeInherited() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:strong", 120.0, 15_000L, 0L);
        timeline.grant("item:weak", 80.0, 12_000L, 2_000L);

        timeline.refresh(15_000L);

        assertNull(timeline.activeSource());
        assertEquals(0, timeline.size());
    }

    @Test
    void equalLaterShieldImmediatelyRefreshesValueAndDuration() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:first", 100.0, 10_000L, 0L);
        timeline.damage(60.0);
        timeline.grant("item:second", 100.0, 12_000L, 2_000L);

        assertEquals("item:second", timeline.activeSource());
        assertEquals(100.0, timeline.activeRemaining());
        assertEquals(12_000L, timeline.activeExpiresAt());
        assertEquals(1, timeline.size());
    }

    @Test
    void copiedTimelineCanBeChangedWithoutMutatingOriginal() {
        WeaponShieldTimeline original = new WeaponShieldTimeline();
        original.grant("item:a", 100.0, 10_000L, 0L);
        WeaponShieldTimeline copy = original.copy();

        copy.damage(25.0);

        assertEquals(100.0, original.activeRemaining());
        assertEquals(75.0, copy.activeRemaining());
    }

    @Test
    void sameSourceCanRefreshToALowerValue() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:a", 120.0, 10_000L, 0L);

        timeline.grant("item:a", 80.0, 14_000L, 4_000L);

        assertEquals(80.0, timeline.activeRemaining());
        assertEquals(80.0, timeline.activeCapacity());
        assertEquals(14_000L, timeline.activeExpiresAt());
    }

    @Test
    void weakerShieldIsComparedWithCapacityRatherThanDamagedRemainder() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:strong", 120.0, 10_000L, 0L);
        timeline.damage(100.0);

        timeline.grant("item:weak", 80.0, 13_000L, 3_000L);

        assertEquals("item:strong", timeline.activeSource());
        assertEquals(20.0, timeline.activeRemaining());
        assertEquals(2, timeline.size());
    }

    @Test
    void shieldAbsorbsImmediatelyBeforeButNotAtItsExpiry() {
        WeaponShieldTimeline before = new WeaponShieldTimeline();
        before.grant("item:a", 100.0, 10_000L, 0L);
        before.refresh(9_999L);
        assertEquals(25.0, before.damage(25.0));

        WeaponShieldTimeline atExpiry = new WeaponShieldTimeline();
        atExpiry.grant("item:a", 100.0, 10_000L, 0L);
        atExpiry.refresh(10_000L);
        assertEquals(0.0, atExpiry.damage(25.0));
        assertNull(atExpiry.activeSource());
    }

    @Test
    void evenSlightlyWeakerShieldWaitsForInheritance() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:strong", 120.0, 10_000L, 0L);

        timeline.grant("item:slightly-weak", 119.9999995, 12_000L, 2_000L);

        assertEquals("item:strong", timeline.activeSource());
        assertEquals(2, timeline.size());
    }

    @Test
    void recastingPendingSourceRefreshesItsOfferWithoutDuplicatingIt() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:strong", 120.0, 10_000L, 0L);
        timeline.grant("item:weak", 60.0, 11_000L, 1_000L);

        timeline.grant("item:weak", 80.0, 15_000L, 5_000L);

        assertEquals(2, timeline.size());
        assertEquals("item:strong", timeline.activeSource());
        timeline.refresh(10_000L);
        assertEquals("item:weak", timeline.activeSource());
        assertEquals(80.0, timeline.activeRemaining());
        assertEquals(15_000L, timeline.activeExpiresAt());
    }

    @Test
    void pendingShieldsInheritByCapacityAndKeepIndependentExpiries() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:active", 120.0, 10_000L, 0L);
        timeline.grant("item:last", 60.0, 13_000L, 3_000L);
        timeline.grant("item:next", 80.0, 12_000L, 2_000L);

        timeline.refresh(10_000L);
        assertEquals("item:next", timeline.activeSource());
        assertEquals(12_000L, timeline.activeExpiresAt());

        timeline.refresh(12_000L);
        assertEquals("item:last", timeline.activeSource());
        assertEquals(13_000L, timeline.activeExpiresAt());
    }

    @Test
    void excessDamageConsumesOnlyTheRemainingShield() {
        WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        timeline.grant("item:a", 40.0, 10_000L, 0L);

        assertEquals(40.0, timeline.damage(100.0));
        assertEquals(0.0, timeline.activeRemaining());
    }
}
