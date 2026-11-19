package com.karan.trueup.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByTenantId(UUID tenantId);

    List<Account> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    Optional<Account> findByTenantIdAndCode(UUID tenantId, String code);
}