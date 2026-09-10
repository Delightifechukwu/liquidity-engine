package com.quidax.liquidityengine.settlement.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "account_balances")
@IdClass(AccountBalanceEntity.Key.class)
public class AccountBalanceEntity {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Id
    private String asset;

    @Column(nullable = false, precision = 38, scale = 8)
    private BigDecimal balance;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected AccountBalanceEntity() {}

    public AccountBalanceEntity(UUID accountId, String asset, BigDecimal balance, Instant updatedAt) {
        this.accountId = accountId;
        this.asset = asset;
        this.balance = balance;
        this.updatedAt = updatedAt;
    }

    public UUID getAccountId() { return accountId; }
    public String getAsset() { return asset; }
    public BigDecimal getBalance() { return balance; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
        this.updatedAt = Instant.now();
    }

    /** Throws if the debit would take the balance negative — enforced here, at the point of
     *  mutation, rather than trusted to have been checked upstream. */
    public void debit(BigDecimal amount) {
        BigDecimal newBalance = this.balance.subtract(amount);
        if (newBalance.signum() < 0) {
            throw new InsufficientBalanceException(accountId, asset, balance, amount);
        }
        this.balance = newBalance;
        this.updatedAt = Instant.now();
    }

    public static class Key implements Serializable {
        private UUID accountId;
        private String asset;

        public Key() {}
        public Key(UUID accountId, String asset) { this.accountId = accountId; this.asset = asset; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(accountId, key.accountId) && Objects.equals(asset, key.asset);
        }

        @Override
        public int hashCode() { return Objects.hash(accountId, asset); }
    }
}
