package com.rates252;

import java.time.LocalDate;

/**
 * One issuer call entry: on {@code date} the issuer may buy the bond back at
 * {@code pricePer100} per 100 of face (ex the coupon paid on that date).
 *
 * @param date        call date; must be a coupon date strictly after settlement
 *                    and strictly before maturity
 * @param pricePer100 positive finite call price per 100 of face
 */
public record CallDate(LocalDate date, double pricePer100) {
    public CallDate {
        Validate.requireDate(date, "call date");
        if (!(pricePer100 > 0.0) || !Double.isFinite(pricePer100)) {
            throw new IllegalArgumentException(
                    "call price per 100 must be positive and finite");
        }
    }
}
