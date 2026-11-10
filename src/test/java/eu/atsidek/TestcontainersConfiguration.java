package eu.atsidek;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Bendra Testcontainers konfigūracija visiems @SpringBootTest testams (F0 fazė: realus PostgreSQL
 * vietoj H2). @ServiceConnection automatiškai sukonfigūruoja spring.datasource.* testo kontekstui –
 * nereikia @DynamicPropertySource. Konteineris startuoja vieną kartą ir yra bendras tarp testų klasių
 * (Testcontainers Ryuk pats jį sustabdo po visų testų).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
}
