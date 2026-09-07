package com.example.demo.auth.repository;

import com.example.demo.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link User}.
 *
 * <p>Not called out explicitly in the module tree in the task description
 * (which only lists model/dto/controller/service for {@code auth/}), but a
 * repository is required for {@code AuthService} to look up users by email
 * and is placed here, inside the {@code auth} package, to respect the
 * isolation boundaries of this task.</p>
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
