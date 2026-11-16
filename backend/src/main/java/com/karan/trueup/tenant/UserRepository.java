package com.karan.trueup.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select (count(u) > 0) from User u where lower(u.email) = lower(:email)")
    boolean emailExists(@Param("email") String email);

    @Query("select u from User u join fetch u.tenant where lower(u.email) = lower(:email)")
    Optional<User> findByEmailWithTenant(@Param("email") String email);
}