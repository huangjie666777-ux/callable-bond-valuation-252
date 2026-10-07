package com.rates252;

import java.time.LocalDate;

/**
 * Per-node call decision detail at one call date.
 *
 * @param date              call date (a coupon date before maturity)
 * @param nodeIndex         node j within the tree level
 * @param shortRate         tree short rate at the node
 * @param continuationValue ex-coupon value of keeping the bond alive
 * @param callPricePer100   contractual call price per 100 (ex current coupon)
 * @param exercised         true when continuation exceeds the call price
 */
public record CallExerciseInfo(LocalDate date, int nodeIndex, double shortRate,
                               double continuationValue, double callPricePer100,
                               boolean exercised) {
}
