package com.payledger.payment.bank;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fault knobs of the stub bank (ADR 0011); rates are probabilities in [0, 1], seed 0 means random. */
@ConfigurationProperties("payledger.mock-bank")
public record MockBankProperties(boolean enabled, int latencyMinMs, int latencyMaxMs, double declineRate,
                                 double timeoutRate, double lostResponseRate, Duration stall, long seed) {
}
