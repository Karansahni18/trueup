package com.karan.trueup.banking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "bank_transactions")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "import_id", nullable = false, updatable = false)
    private UUID importId;

    @Column(name = "posted_date", nullable = false, updatable = false)
    private LocalDate postedDate;

    @Column(nullable = false, updatable = false, length = 500)
    private String description;

    @Column(updatable = false, length = 100)
    private String reference;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    protected BankTransaction() {
        // required by JPA
    }

    public BankTransaction(UUID tenantId, UUID importId, LocalDate postedDate, String description,
                           String reference, long amountMinor, String currency) {
        this.tenantId = tenantId;
        this.importId = importId;
        this.postedDate = postedDate;
        this.description = description;
        this.reference = reference;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getImportId() {
        return importId;
    }

    public LocalDate getPostedDate() {
        return postedDate;
    }

    public String getDescription() {
        return description;
    }

    public String getReference() {
        return reference;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }
}