package com.payledger.auth;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

    record User(String username, String passwordHash, String role) {
    }

    private final JdbcTemplate jdbc;

    UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<User> find(String username) {
        return jdbc.query("SELECT username, password_hash, role FROM users WHERE username = ?",
                (rs, i) -> new User(rs.getString("username"), rs.getString("password_hash"), rs.getString("role")),
                username).stream().findFirst();
    }

    /** Throws DuplicateKeyException when the username exists. */
    void insert(String username, String passwordHash, String role) {
        jdbc.update("INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?)", username, passwordHash, role);
    }

    long count() {
        return jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
    }
}
