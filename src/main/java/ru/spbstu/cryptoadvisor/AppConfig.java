package ru.spbstu.cryptoadvisor;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.PropertySource;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration
@ComponentScan("ru.spbstu.cryptoadvisor")
@PropertySource("classpath:application.properties")
@EnableWebFlux
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
    public DSLContext dslContext(DataSource dataSource) throws SQLException {
        return DSL.using(dataSource.getConnection(), SQLDialect.POSTGRES);
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }
}
