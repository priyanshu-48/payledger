package com.payledger.ledger.recon;

import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

/** load the bank file -> compare with the ledger -> write the run's counters (ADR 0017). */
@Configuration
class ReconJobConfig {

    @Bean
    Job reconJob(JobRepository repository, Step loadBankFile, Step compareWithLedger, Step finishRun) {
        return new JobBuilder("reconJob", repository)
                .start(loadBankFile)
                .next(compareWithLedger)
                .next(finishRun)
                .build();
    }

    /** Chunked and restartable: the reader remembers how many lines it has committed. */
    @Bean
    Step loadBankFile(JobRepository repository, PlatformTransactionManager transactions,
                      FlatFileItemReader<BankRow> bankFileReader, BankRowWriter writer,
                      @Value("${ledger.recon.chunk-size}") int chunkSize) {
        return new StepBuilder("loadBankFile", repository)
                .<BankRow, BankRow>chunk(chunkSize, transactions)
                .reader(bankFileReader)
                .writer(writer)
                .build();
    }

    @Bean
    Step compareWithLedger(JobRepository repository, PlatformTransactionManager transactions, CompareTasklet tasklet) {
        return new StepBuilder("compareWithLedger", repository).tasklet(tasklet, transactions).build();
    }

    @Bean
    Step finishRun(JobRepository repository, PlatformTransactionManager transactions, FinishTasklet tasklet) {
        return new StepBuilder("finishRun", repository).tasklet(tasklet, transactions).build();
    }

    @Bean
    @StepScope
    FlatFileItemReader<BankRow> bankFileReader(@Value("#{jobParameters['file']}") String file,
                                               @Value("#{jobParameters['runId']}") String runId) {
        return new FlatFileItemReaderBuilder<BankRow>()
                .name("bankFileReader")
                .resource(new FileSystemResource(file))
                .linesToSkip(1) // header
                .lineMapper(new BankRowLineMapper(UUID.fromString(runId)))
                .build();
    }
}
