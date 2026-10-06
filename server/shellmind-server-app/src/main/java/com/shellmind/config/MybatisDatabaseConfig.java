package com.shellmind.config;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
public class MybatisDatabaseConfig {

    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties properties = new Properties();
        properties.setProperty("MySQL", "mysql");
        properties.setProperty("H2", "h2");
        provider.setProperties(properties);
        return provider;
    }

    @Bean
    @Primary
    public SqlSessionFactoryBean sqlSessionFactoryBean(
            javax.sql.DataSource dataSource,
            DatabaseIdProvider databaseIdProvider) {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        factoryBean.setConfigLocation(resolver.getResource("classpath:/mybatis/config/mybatis-config.xml"));
        try {
            factoryBean.setMapperLocations(resolver.getResources("classpath*:/mybatis/mapper/*.xml"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load MyBatis mapper", e);
        }
        try {
            factoryBean.setDatabaseIdProvider(databaseIdProvider);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize MyBatis databaseIdProvider", e);
        }
        return factoryBean;
    }
}
