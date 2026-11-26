package com.karan.trueup.banking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "bank_statement_imports")
public class BankStatementImport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false, length = 255)
    private String filename;

    @Column(name = "file_hash", nullable = false, updatable = false, length = 64)
    private String fileHash;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "row_count", nullable = false, updatable = false)
    private int rowCount;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt = Instant.now();

    protected BankStatementImport() {
        // required by JPA
    }

    public BankStatementImport(UUID tenantId, String filename, String fileHash, String currency, int rowCount) {
        this.tenantId = tenantId;
        this.filename = filename;
        this.fileHash = fileHash;
        this.currency = currency;
        this.rowCount = rowCount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getFilename() {
        return filename;
    }

    public String getFileHash() {
        return fileHash;
    }

    public String getCurrency() {
        return currency;
    }

    public int getRowCount() {
        return rowCount;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}