package com.quidax.liquidityengine.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Fixed-scale monetary value. Wrapping BigDecimal here (rather than passing it around raw)
 * is deliberate: every arithmetic op enforces the same scale + rounding mode, so precision
 * decisions are made once, in one place, instead of at every call site across the codebase.
 *
 * (De)serializes as a plain decimal (via @JsonCreator/@JsonValue) rather than an object,
 * since Jackson has no default constructor or bean accessor to work with otherwise —
 * required for Money-bearing events (e.g. OrderCreatedEvent's Order) to survive a Kafka
 * round-trip.
 */
public final class Money implements Comparable<Money> {

    public static final int SCALE = 8; // sufficient for crypto base units (satoshi-level)
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    @JsonCreator
    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        return new Money(amount.setScale(SCALE, RoundingMode.HALF_UP));
    }

    public static Money of(String amount) {
        return of(new BigDecimal(amount));
    }

    @JsonValue
    public BigDecimal value() {
        return amount;
    }

    public Money add(Money other) {
        return Money.of(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return Money.of(this.amount.subtract(other.amount));
    }

    public Money multiply(BigDecimal factor) {
        return Money.of(this.amount.multiply(factor));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        return this.amount.compareTo(other.amount) > 0;
    }

    public boolean isLessThan(Money other) {
        return this.amount.compareTo(other.amount) < 0;
    }

    @Override
    public int compareTo(Money o) {
        return this.amount.compareTo(o.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
