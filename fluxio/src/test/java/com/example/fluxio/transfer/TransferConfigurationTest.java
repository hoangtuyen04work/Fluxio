package com.example.fluxio.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.fluxio.transfer.storage.MultipartObjectStore;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TransferConfigurationTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(TransferConfiguration.class)
            .withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
            .withBean(TransactionTemplate.class, () ->
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)))
            .withPropertyValues(
                    "fluxio.storage.endpoint=http://127.0.0.1:9000",
                    "fluxio.storage.access-key=local-access",
                    "fluxio.storage.secret-key=local-secret",
                    "fluxio.storage.bucket=fluxio");

    @Test
    void staysDisabledWithoutExplicitDatasourceUrl() {
        context.run(result -> assertThat(result).doesNotHaveBean(MultipartObjectStore.class));
    }

    @Test
    void activatesWhenDatasourceUrlIsConfigured() {
        context.withPropertyValues("spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/fluxio")
                .run(result -> assertThat(result).hasSingleBean(MultipartObjectStore.class));
    }
}
