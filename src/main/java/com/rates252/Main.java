package com.rates252;

import java.time.LocalDate;
import java.util.List;

/**
 * Valuation and risk example: one deposit, three annual swaps and a 3y bond.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        LocalDate valueDate = LocalDate.of(2026, 6, 28);
        CurveConfig config = CurveConfig.defaults();
        CurveBootstrapper bootstrapper = new CurveBootstrapper(config);

        List<DepositQuote> deposits = List.of(
                new DepositQuote("DEP-6M", LocalDate.of(2026, 12, 28), 0.0250));
        List<SwapQuote> swaps = List.of(
                new SwapQuote("SWAP-1Y", List.of(LocalDate.of(2027, 6, 28)), 0.0300),
                new SwapQuote("SWAP-2Y",
                        List.of(LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28)), 0.0325),
                new SwapQuote("SWAP-3Y",
                        List.of(LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                                LocalDate.of(2029, 6, 28)), 0.0350));

        BootstrapResult result = bootstrapper.bootstrap(valueDate, deposits, swaps);
        DiscountCurve curve = result.curve();

        System.out.println("rates252 valuation and risk example");
        System.out.println("value date: " + valueDate);
        System.out.println();
        System.out.println("bootstrapped nodes (date, D):");
        for (int i = 0; i < curve.nodeCount(); i++) {
            System.out.printf("  %s  D = %.10f%n", curve.nodeDate(i),
                    curve.discountAtNode(i));
        }
        System.out.println();
        System.out.println("quote reproduction:");
        for (InstrumentRepricing r : result.repricings()) {
            System.out.printf("  %-8s market=%.6f reproduced=%.10f residual=%.2e%n",
                    r.id(), r.marketQuote(), r.reproducedQuote(), r.residual());
        }

        Bond bond = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2025, 6, 28), LocalDate.of(2026, 6, 28),
                        LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                        LocalDate.of(2029, 6, 28)),
                100.0, 0.0400);
        LocalDate settlementDate = LocalDate.of(2026, 9, 15);
        BondPricer pricer = new BondPricer();
        BondPrice price = pricer.price(bond, settlementDate, curve);

        System.out.println();
        System.out.println("bond settlement: " + settlementDate);
        System.out.printf("dirty price per 100: %.6f%n", price.dirtyPrice());
        System.out.printf("accrued per 100:    %.6f%n", price.accruedInterest());
        System.out.printf("clean price per 100: %.6f%n", price.cleanPrice());
        System.out.println("surviving cash flows:");
        for (BondCashflow flow : price.cashflows()) {
            System.out.printf("  %s alpha=%.6f coupon=%.4f principal=%.2f%n",
                    flow.paymentDate(), flow.yearFraction(), flow.coupon(), flow.principal());
        }

        RiskEngine risk = new RiskEngine(config);
        Dv01Report report = risk.dv01(valueDate, deposits, swaps, bond, settlementDate);
        System.out.println();
        System.out.println("net-price DV01 per quote (bump +/-1 bp, full rebuild):");
        for (QuoteDv01 dv : report.results()) {
            if (dv.failed()) {
                System.out.printf("  %-8s FAILED: %s%n", dv.id(), dv.failureReason());
            } else {
                System.out.printf("  %-8s %.6f%n", dv.id(), dv.dv01());
            }
        }

        // Callable bond demo: 5% bond maturing 2027-06-28, callable at the
        // first two coupon dates; 2-day grid, 1.5% annual short-rate vol.
        Bond callableBond = new Bond(LocalDate.of(2026, 6, 28),
                List.of(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 3, 28),
                        LocalDate.of(2027, 6, 28)),
                100.0, 0.0500);
        List<CallDate> callSchedule = List.of(
                new CallDate(LocalDate.of(2026, 12, 28), 101.00),
                new CallDate(LocalDate.of(2027, 3, 28), 100.50));
        double sigma = 0.015;
        int stepDays = 2;
        CallableBondPrice callable = new CallableBondPricer().price(
                callableBond, settlementDate, curve, sigma, stepDays, callSchedule);

        System.out.println();
        System.out.println("callable bond (5% to 2027-06-28, calls at 101.00/100.50,");
        System.out.println("settlement " + settlementDate + ", sigma=" + sigma
                + ", step=" + stepDays + "d, steps=" + (callable.calibrationResiduals().size()) + "):");
        System.out.printf("  callable dirty per 100:     %.6f%n", callable.dirtyPrice());
        System.out.printf("  accrued per 100:            %.6f%n", callable.accruedInterest());
        System.out.printf("  callable clean per 100:     %.6f%n", callable.cleanPrice());
        System.out.printf("  non-callable dirty per 100: %.6f%n", callable.nonCallableDirtyPrice());
        System.out.printf("  call option cost per 100:   %.6f%n", callable.optionCost());
        double worstResidual = callable.calibrationResiduals().stream()
                .mapToDouble(Math::abs).max().orElse(0.0);
        System.out.printf("  max |tree calibration residual|: %.2e%n", worstResidual);
        System.out.println("  call-date summary (nodes, calls, deepest-in-the-money node):");
        java.time.LocalDate current = null;
        java.util.List<CallExerciseInfo> day = new java.util.ArrayList<>();
        for (CallExerciseInfo info : callable.exercises()) {
            if (!info.date().equals(current)) {
                printCallDay(day);
                day.clear();
                current = info.date();
            }
            day.add(info);
        }
        printCallDay(day);
    }

    private static void printCallDay(List<CallExerciseInfo> day) {
        if (day.isEmpty()) {
            return;
        }
        CallExerciseInfo deepest = day.stream()
                .max(java.util.Comparator.comparingDouble(
                        i -> i.continuationValue() - i.callPricePer100()))
                .orElseThrow();
        long calls = day.stream().filter(CallExerciseInfo::exercised).count();
        System.out.printf("  %s call at %.2f: %d/%d nodes CALL; deepest j=%d r=%.5f cont=%.4f%n",
                deepest.date(), deepest.callPricePer100(), calls, day.size(),
                deepest.nodeIndex(), deepest.shortRate(), deepest.continuationValue());
    }
}
