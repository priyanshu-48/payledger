package com.payledger.ledger;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/** One Postgres container per JVM, shared by every integration test (Spring caches contexts on top). */
abstract class AbstractPostgresTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            // many cached Spring contexts each keep a connection pool; the harness adds a 64-connection one
            .withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
    }
}
