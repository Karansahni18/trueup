package com.karan.trueup.ledger;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "entry_date", nullable = false, updatable = false)
    private LocalDate entryDate;

    @Column(nullable = false, updatable = false, length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    private List<JournalLine> lines = new ArrayList<>();

    protected JournalEntry() {
        // required by JPA
    }

    public JournalEntry(UUID tenantId, LocalDate entryDate, String description) {
        this.tenantId = tenantId;
        this.entryDate = entryDate;
        this.description = description;
    }

    public JournalLine addLine(Account account, Direction direction, long amountMinor) {
        JournalLine line = new JournalLine(this, account, direction, amountMinor);
        lines.add(line);
        return line;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<JournalLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}