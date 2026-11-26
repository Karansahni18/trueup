package com.karan.trueup.banking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    List<BankTransaction> findTop200ByTenantIdOrderByPostedDateDescIdDesc(UUID tenantId);
}