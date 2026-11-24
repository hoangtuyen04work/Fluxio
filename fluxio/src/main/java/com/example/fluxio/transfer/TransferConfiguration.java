package com.example.fluxio.transfer;

import com.example.fluxio.transfer.application.IdempotencyService;
import com.example.fluxio.transfer.application.UploadApplicationService;
import com.example.fluxio.transfer.application.UploadCleanupJob;
import com.example.fluxio.transfer.application.UploadReconciliationService;
import com.example.fluxio.file.application.DownloadTicketService;
import com.example.fluxio.transfer.persistence.JdbcUploadRepository;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import com.example.fluxio.transfer.storage.S3MultipartObjectStore;
import com.example.fluxio.transfer.storage.StorageProperties;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "spring.datasource.url")
class TransferConfiguration {
    @Bean
    Clock transferClock() {
        return Clock.systemUTC();
    }

    @Bean
    UploadRepository uploadRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        return new JdbcUploadRepository(jdbc, transactions);
    }

    @Bean
    IdempotencyService idempotencyService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        return new IdempotencyService(jdbc, transactions);
    }

    @Bean(destroyMethod = "close")
    MultipartObjectStore multipartObjectStore(
            @Value("${fluxio.storage.endpoint}") String endpoint,
            @Value("${fluxio.storage.public-endpoint:${fluxio.storage.endpoint}}") String publicEndpoint,
            @Value("${fluxio.storage.region:us-east-1}") String region,
            @Value("${fluxio.storage.access-key}") String accessKey,
            @Value("${fluxio.storage.secret-key}") String secretKey,
            @Value("${fluxio.storage.bucket}") String bucket) {
        return new S3MultipartObjectStore(new StorageProperties(
                endpoint, publicEndpoint, region, accessKey, secretKey, bucket,
                Duration.ofSeconds(30), Duration.ofMinutes(15)));
    }

    @Bean
    UploadApplicationService uploadApplicationService(
            UploadRepository uploads,
            MultipartObjectStore storage,
            Clock transferClock) {
        return new UploadApplicationService(uploads, storage, transferClock, 8L * 1024 * 1024);
    }

    @Bean
    UploadReconciliationService uploadReconciliationService(
            UploadRepository uploads, MultipartObjectStore storage) {
        return new UploadReconciliationService(uploads, storage);
    }

    @Bean
    UploadCleanupJob uploadCleanupJob(
            UploadRepository uploads, MultipartObjectStore storage, Clock transferClock) {
        return new UploadCleanupJob(uploads, storage, transferClock, 100);
    }

    @Bean
    DownloadTicketService downloadTicketService(
            UploadRepository uploads, MultipartObjectStore storage, Clock transferClock) {
        return new DownloadTicketService(uploads, storage, transferClock);
    }
}
