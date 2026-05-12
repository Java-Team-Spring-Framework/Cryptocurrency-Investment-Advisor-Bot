package ru.spbstu.cryptoadvisor.config;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ComponentScan("ru.spbstu.cryptoadvisor")
@PropertySource("classpath:application.properties")
@EnableScheduling
public class AppConfig {

    @Value("${postgres.url}")
    private String dbUrl;

    @Value("${postgres.username}")
    private String dbUser;

    @Value("${postgres.password}")
    private String dbPassword;

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(dbUrl);
        dataSource.setUsername(dbUser);
        dataSource.setPassword(dbPassword);
        return dataSource;
    }

        @Bean
        @DependsOn("databaseInitializer")
        public DSLContext dslContext(DataSource dataSource) {
            return DSL.using(dataSource, SQLDialect.POSTGRES);
        }

    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }

    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper().registerModule(
            new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()
        );
    }
}
