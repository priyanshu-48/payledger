package com.payledger.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling, as the service layer sees it: the token's subject and roles. Controllers build it from the
 * request; internal flows (Kafka consumer, batch job) use {@link #system()}. Ownership checks take a Caller
 * so they apply to every entry point, not only to HTTP (ADR 0019).
 */
public record Caller(String subject, Set<String> roles) {

    public static final String ADMIN = "ADMIN";
    public static final String USER = "USER";
    public static final String MERCHANT = "MERCHANT";

    public Caller {
        roles = Set.copyOf(roles);
    }

    /** For work the platform does by itself (consuming events, running batch jobs); never built from a request. */
    public static Caller system() {
        return new Caller("system", Set.of(ADMIN));
    }

    public static Caller of(String subject, String... roles) {
        return new Caller(subject, Set.of(roles));
    }

    public static Caller from(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AccessDeniedException("authentication required");
        }
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toUnmodifiableSet());
        return new Caller(jwt.getSubject(), roles);
    }

    public boolean isAdmin() {
        return roles.contains(ADMIN);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    @Override
    public String toString() {
        return "Caller[" + subject + " " + roles + "]";
    }
}
