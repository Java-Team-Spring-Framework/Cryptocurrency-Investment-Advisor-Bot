package ru.spbstu.cryptoadvisor;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class SchemaInitializer implements InitializingBean {

    private final DataSource dataSource;

    @Value("classpath:schema.sql")
    private Resource schemaSql;

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(schemaSql);
        populator.execute(dataSource);
    }
}
