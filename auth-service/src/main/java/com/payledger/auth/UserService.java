package com.payledger.auth;

import com.payledger.security.AuditLog;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Users and passwords (ADR 0018). Passwords are only ever handled here, hashed with BCrypt, and never logged. */
@Service
class UserService {

    static final Set<String> ROLES = Set.of("USER", "MERCHANT", "ADMIN");

    /** Thrown for input the caller can fix; the message never contains the password. */
    static class InvalidUserException extends RuntimeException {
        InvalidUserException(String message) {
            super(message);
        }
    }

    static class UsernameTakenException extends RuntimeException {
        UsernameTakenException() {
            super("username already exists");
        }
    }

    private final UserRepository users;
    private final AuditLog audit;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    /** Compared against when the username is unknown, so a missing user costs as much time as a wrong password. */
    private final String dummyHash = encoder.encode("not-a-real-password");

    UserService(UserRepository users, AuditLog audit) {
        this.users = users;
        this.audit = audit;
    }

    /** Empty for an unknown user or a wrong password: callers cannot tell which. */
    Optional<UserRepository.User> authenticate(String username, String password) {
        Optional<UserRepository.User> user = users.find(username);
        boolean matches = encoder.matches(password, user.map(UserRepository.User::passwordHash).orElse(dummyHash));
        return user.filter(u -> matches);
    }

    @Transactional
    void create(String actor, String username, String password, String role) {
        validate(username, password, role);
        try {
            users.insert(username, encoder.encode(password), role);
        } catch (DuplicateKeyException e) {
            throw new UsernameTakenException();
        }
        audit.record(actor, "user.created", username, Map.of("role", role));
    }

    long count() {
        return users.count();
    }

    private static void validate(String username, String password, String role) {
        if (username == null || !username.matches("[a-z0-9._-]{3,64}")) {
            throw new InvalidUserException("username must be 3-64 characters: lowercase letters, digits, . _ -");
        }
        // BCrypt only uses the first 72 bytes, so longer passwords would be silently truncated
        if (password == null || password.length() < 10 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new InvalidUserException("password must be 10-72 bytes long");
        }
        if (role == null || !ROLES.contains(role)) {
            throw new InvalidUserException("role must be one of " + ROLES);
        }
    }
}
