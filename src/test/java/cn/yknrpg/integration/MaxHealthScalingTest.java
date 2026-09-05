package cn.yknrpg.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MaxHealthScalingTest {
    @Test
    void keepsOneToOneHealthWithoutScalars() {
        assertEquals(120.0, MaxHealthScaling.rawAddNumber(120.0, 1.0), 0.000001);
    }

    @Test
    void cancelsTankTotalScalar() {
        double raw = MaxHealthScaling.rawAddNumber(120.0, 1.3);
        assertEquals(120.0, raw * 1.3, 0.000001);
    }

    @Test
    void cancelsCombinedBaseAndTotalScalars() {
        double combinedScale = 1.2 * 1.4;
        double raw = MaxHealthScaling.rawAddNumber(120.0, combinedScale);
        assertEquals(120.0, raw * combinedScale, 0.000001);
    }

    @Test
    void rejectsInvalidValuesAndNonPositiveScales() {
        assertEquals(0.0, MaxHealthScaling.rawAddNumber(Double.NaN, 1.0));
        assertEquals(0.0, MaxHealthScaling.rawAddNumber(120.0, Double.POSITIVE_INFINITY));
        assertEquals(0.0, MaxHealthScaling.rawAddNumber(120.0, 0.0));
        assertEquals(0.0, MaxHealthScaling.rawAddNumber(120.0, -1.0));
    }
}
