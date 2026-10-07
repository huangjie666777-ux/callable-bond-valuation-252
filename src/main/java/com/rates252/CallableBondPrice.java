package com.rates252;

import java.util.List;

/**
 * Callable bond valuation output, all prices per 100 of face value.
 *
 * @param dirtyPrice           callable full (gross) price per 100
 * @param accruedInterest      accrued interest per 100 (zero on a coupon date)
 * @param cleanPrice           callable net price per 100 = dirty - accrued
 * @param nonCallableDirtyPrice full price per 100 of the same bond without calls
 * @param optionCost           nonCallableDirty - dirty (issuer call option cost)
 * @param calibrationResiduals per-level tree reproduction residuals
 * @param exercises            per-node call decision details at each call date
 */
public record CallableBondPrice(double dirtyPrice, double accruedInterest,
                                double cleanPrice, double nonCallableDirtyPrice,
                                double optionCost, List<Double> calibrationResiduals,
                                List<CallExerciseInfo> exercises) {
    public CallableBondPrice {
        calibrationResiduals = List.copyOf(calibrationResiduals);
        exercises = List.copyOf(exercises);
    }
}
