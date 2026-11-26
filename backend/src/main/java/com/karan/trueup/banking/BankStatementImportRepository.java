package com.karan.trueup.banking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BankStatementImportRepository extends JpaRepository<BankStatementImport, UUID> {

    boolean existsByTenantIdAndFileHash(UUID tenantId, String fileHash);
}