package com.payledger.payment;

import com.payledger.outbox.OutboxConfiguration;
import com.payledger.payment.bank.MockBankProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MockBankProperties.class)
@Import(OutboxConfiguration.class)
class PaymentConfig {
}
