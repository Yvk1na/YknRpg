package cn.yknrpg.integration;

final class MaxHealthScaling {
    private static final double EPSILON = 0.000001;

    private MaxHealthScaling() {
    }

    static double rawAddNumber(double actualAmount, double attributeScale) {
        if (!Double.isFinite(actualAmount)
                || !Double.isFinite(attributeScale)
                || attributeScale <= EPSILON) return 0.0;
        return actualAmount / attributeScale;
    }
}
