package com.rates252;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Recombining binomial short-rate tree calibrated to a discount curve.
 * Level i node j carries the short rate
 * {@code r = a_i + (2j - i) * sigma * sqrt(dt)} with {@code dt = stepDays/365}
 * and up/down probabilities of one half; one-step discounting is continuous,
 * {@code exp(-r * dt)}. The shifts a_i are solved level by level so the
 * cumulative state prices reproduce {@code D(date_i) / D(settlement)}.
 * Negative rates and zero volatility are allowed.
 */
public final class ShortRateTree {
    public static final int MAX_STEPS = 300;
    private static final double CALIBRATION_TOLERANCE = 1.0e-10;
    private static final int MAX_BISECTION_ITERATIONS = 200;

    private final LocalDate settlementDate;
    private final int stepDays;
    private final double sigma;
    private final double dt;
    private final int stepCount;
    private final LocalDate[] dates;
    private final double[] shifts;
    private final double[] targetDiscounts;
    private final List<Double> calibrationResiduals;

    /**
     * Builds and calibrates the tree. The settlement-to-maturity span must be
     * an exact multiple of {@code stepDays} with at most {@link #MAX_STEPS}
     * steps, and the whole grid must lie inside the curve range.
     */
    public ShortRateTree(DiscountCurve curve, LocalDate settlementDate,
                         LocalDate maturityDate, int stepDays, double sigma) {
        if (curve == null) {
            throw new IllegalArgumentException("curve must not be null");
        }
        Validate.requireDate(settlementDate, "settlement date");
        Validate.requireDate(maturityDate, "maturity date");
        if (!maturityDate.isAfter(settlementDate)) {
            throw new IllegalArgumentException("maturity must be after settlement");
        }
        if (stepDays <= 0) {
            throw new IllegalArgumentException("step days must be positive, got " + stepDays);
        }
        if (sigma < 0.0 || !Double.isFinite(sigma)) {
            throw new IllegalArgumentException(
                    "sigma must be non-negative and finite, got " + sigma);
        }
        long totalDays = ChronoUnit.DAYS.between(settlementDate, maturityDate);
        if (totalDays % stepDays != 0L) {
            throw new IllegalArgumentException("settlement-to-maturity span of " + totalDays
                    + " days is not a multiple of the " + stepDays + "-day step");
        }
        long steps = totalDays / stepDays;
        if (steps < 1 || steps > MAX_STEPS) {
            throw new IllegalArgumentException(
                    "step count " + steps + " outside [1, " + MAX_STEPS + "]");
        }
        this.settlementDate = settlementDate;
        this.stepDays = stepDays;
        this.sigma = sigma;
        this.dt = stepDays / DayCount.DAYS_PER_YEAR;
        this.stepCount = (int) steps;
        this.dates = new LocalDate[stepCount + 1];
        this.targetDiscounts = new double[stepCount + 1];
        double settlementDf = curve.discountFactor(settlementDate);
        for (int i = 0; i <= stepCount; i++) {
            dates[i] = settlementDate.plusDays((long) i * stepDays);
            // Throws if the grid leaves the curve range.
            targetDiscounts[i] = curve.discountFactor(dates[i]) / settlementDf;
        }
        this.shifts = new double[stepCount];
        this.calibrationResiduals = calibrate();
    }

    private List<Double> calibrate() {
        List<Double> residuals = new ArrayList<>();
        double[] statePrices = new double[] {1.0};
        for (int i = 0; i < stepCount; i++) {
            double target = targetDiscounts[i + 1];
            double shift = solveShift(i, statePrices, target);
            shifts[i] = shift;
            double[] next = new double[i + 2];
            for (int j = 0; j <= i; j++) {
                double discount = Math.exp(-rateAt(i, j) * dt) * 0.5 * statePrices[j];
                next[j] += discount;
                next[j + 1] += discount;
            }
            double reproduced = 0.0;
            for (double q : next) {
                reproduced += q;
            }
            double residual = reproduced - target;
            if (!Double.isFinite(residual)
                    || Math.abs(residual) > CALIBRATION_TOLERANCE) {
                throw new IllegalStateException("short-rate tree calibration failed at level "
                        + i + ": residual " + residual + " against target D=" + target);
            }
            residuals.add(residual);
            statePrices = next;
        }
        return List.copyOf(residuals);
    }

    private double solveShift(int level, double[] statePrices, double target) {
        double lo = -1.0;
        double hi = 1.0;
        int guard = 0;
        while (levelDiscountSum(level, statePrices, lo) < target) {
            lo *= 2.0;
            if (++guard > 200) {
                throw new IllegalStateException("cannot bracket tree shift at level " + level);
            }
        }
        guard = 0;
        while (levelDiscountSum(level, statePrices, hi) > target) {
            hi *= 2.0;
            if (++guard > 200) {
                throw new IllegalStateException("cannot bracket tree shift at level " + level);
            }
        }
        for (int iter = 0; iter < MAX_BISECTION_ITERATIONS; iter++) {
            double mid = 0.5 * (lo + hi);
            if (levelDiscountSum(level, statePrices, mid) > target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double shift = 0.5 * (lo + hi);
        if (!Double.isFinite(shift)) {
            throw new IllegalStateException("non-finite tree shift at level " + level);
        }
        return shift;
    }

    private double levelDiscountSum(int level, double[] statePrices, double shift) {
        double spread = sigma * Math.sqrt(dt);
        double sum = 0.0;
        for (int j = 0; j <= level; j++) {
            double rate = shift + (2.0 * j - level) * spread;
            sum += statePrices[j] * Math.exp(-rate * dt);
        }
        return sum;
    }

    public int stepCount() {
        return stepCount;
    }

    public int stepDays() {
        return stepDays;
    }

    public double dt() {
        return dt;
    }

    public LocalDate date(int step) {
        return dates[step];
    }

    /**
     * Zero-based grid index of a date, or -1 if it is not a grid date.
     */
    public int stepOf(LocalDate date) {
        if (date.isBefore(settlementDate)) {
            return -1;
        }
        long days = ChronoUnit.DAYS.between(settlementDate, date);
        if (days % stepDays != 0L) {
            return -1;
        }
        long step = days / stepDays;
        return step <= stepCount ? (int) step : -1;
    }

    /**
     * Short rate at level i, node j: a_i + (2j - i) * sigma * sqrt(dt).
     */
    public double rateAt(int level, int node) {
        return shifts[level] + (2.0 * node - level) * sigma * Math.sqrt(dt);
    }

    public double shiftAt(int level) {
        return shifts[level];
    }

    /**
     * Per-level reproduction residuals: sum of state prices at level i+1
     * minus D(date_{i+1}) / D(settlement).
     */
    public List<Double> calibrationResiduals() {
        return calibrationResiduals;
    }
}
