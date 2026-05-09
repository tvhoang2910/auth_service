package com.exam_bank.auth_service.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    Page<User> findByRole(Role role, Pageable pageable);

    List<User> findByRoleInAndStatusTrue(Collection<Role> roles);

    long countByStatusFalse();

    long countByRoleIn(Collection<Role> roles);
}
