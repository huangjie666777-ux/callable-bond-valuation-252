package com.rates252;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prices issuer-callable fixed coupon bonds by backward induction on a
 * {@link ShortRateTree}. On a call date the current coupon is paid first,
 * then the issuer compares the ex-coupon continuation value with the call
 * price and takes the smaller; ties continue. After a call no further
 * coupons or principal are paid. Without a call the bond pays coupon plus
 * principal at maturity. Inputs are never mutated.
 */
public final class CallableBondPricer {
    private final BondPricer bondPricer = new BondPricer();

    /**
     * Values a callable bond per 100 of face.
     *
     * @param bond            underlying fixed coupon bond
     * @param settlementDate  settlement in [issue date, maturity)
     * @param curve           discount curve covering the whole grid
     * @param sigma           annualized short-rate volatility, finite and >= 0
     * @param stepDays        grid step in days; must divide the
     *                        settlement-to-maturity span, give at most
     *                        {@link ShortRateTree#MAX_STEPS} steps, and land
     *                        every remaining coupon date on the grid
     * @param callSchedule    call entries on coupon dates strictly after
     *                        settlement and strictly before maturity
     */
    public CallableBondPrice price(Bond bond, LocalDate settlementDate,
                                   DiscountCurve curve, double sigma, int stepDays,
                                   List<CallDate> callSchedule) {
        if (bond == null || curve == null) {
            throw new IllegalArgumentException("bond and curve must not be null");
        }
        Validate.requireDate(settlementDate, "settlement date");
        if (callSchedule == null) {
            throw new IllegalArgumentException("call schedule must not be null");
        }
        LocalDate maturity = bond.maturityDate();
        ShortRateTree tree = new ShortRateTree(curve, settlementDate, maturity,
                stepDays, sigma);
        int steps = tree.stepCount();

        List<BondCashflow> flows = bondPricer.cashflows(bond);
        double scale = 100.0 / bond.faceValue();
        double[] couponAtStep = new double[steps + 1];
        Set<LocalDate> couponDates = new HashSet<>();
        for (BondCashflow flow : flows) {
            couponDates.add(flow.paymentDate());
            if (!flow.paymentDate().isAfter(settlementDate)) {
                continue;
            }
            int step = tree.stepOf(flow.paymentDate());
            if (step < 0) {
                throw new IllegalArgumentException("coupon date " + flow.paymentDate()
                        + " does not fall on the " + stepDays + "-day grid");
            }
            couponAtStep[step] = flow.coupon() * scale;
        }

        Map<Integer, Double> callPriceAtStep = new HashMap<>();
        Set<LocalDate> seenCallDates = new HashSet<>();
        for (CallDate call : callSchedule) {
            if (!seenCallDates.add(call.date())) {
                throw new IllegalArgumentException("duplicate call date " + call.date());
            }
            if (!call.date().isAfter(settlementDate) || !call.date().isBefore(maturity)) {
                throw new IllegalArgumentException("call date " + call.date()
                        + " must be strictly after settlement and before maturity");
            }
            if (!couponDates.contains(call.date())) {
                throw new IllegalArgumentException(
                        "call date " + call.date() + " is not a coupon date");
            }
            int step = tree.stepOf(call.date());
            if (step < 0) {
                throw new IllegalArgumentException("call date " + call.date()
                        + " does not fall on the grid");
            }
            callPriceAtStep.put(step, call.pricePer100());
        }

        // Backward induction in per-100 units. Maturity pays coupon + 100.
        double[] values = new double[steps + 1];
        for (int j = 0; j <= steps; j++) {
            values[j] = couponAtStep[steps] + 100.0;
        }
        List<CallExerciseInfo> exercises = new ArrayList<>();
        double dt = tree.dt();
        for (int i = steps - 1; i >= 0; i--) {
            double[] previous = values;
            values = new double[i + 1];
            Double callPrice = callPriceAtStep.get(i);
            for (int j = 0; j <= i; j++) {
                double continuation = Math.exp(-tree.rateAt(i, j) * dt)
                        * 0.5 * (previous[j] + previous[j + 1]);
                double kept = continuation;
                if (callPrice != null) {
                    boolean exercised = continuation > callPrice;
                    exercises.add(new CallExerciseInfo(tree.date(i), j,
                            tree.rateAt(i, j), continuation, callPrice, exercised));
                    kept = Math.min(continuation, callPrice);
                }
                values[j] = couponAtStep[i] + kept;
            }
        }

        double dirty = values[0];
        if (!Double.isFinite(dirty)) {
            throw new IllegalStateException("non-finite callable bond price");
        }
        double accrued = bondPricer.accruedInterest(bond, settlementDate) * scale;
        BondPrice straight = bondPricer.price(bond, settlementDate, curve);
        double nonCallableDirty = straight.dirtyPrice();
        return new CallableBondPrice(dirty, accrued, dirty - accrued,
                nonCallableDirty, nonCallableDirty - dirty,
                tree.calibrationResiduals(), exercises);
    }
}
