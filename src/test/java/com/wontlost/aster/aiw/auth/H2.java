package com.wontlost.aster.aiw.auth;

import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 每个测试一个独立的 H2（PostgreSQL 模式）库，并执行生产同一份 schema-aiw.sql。 */
final class H2 {
    private H2() {}

    static JdbcTemplate fresh() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        var populator = new ResourceDatabasePopulator(new ClassPathResource("schema-aiw.sql"));
        populator.execute(ds);
        // 再执行一次：脚本必须幂等（每次启动都会运行）
        populator.execute(ds);
        return new FlakyJdbc(ds);
    }

    static FlakyJdbc flaky() {
        return (FlakyJdbc) fresh();
    }
}
