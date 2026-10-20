package com.wontlost.aster.aiw.auth;

import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 可切换「数据库宕机」的 JdbcTemplate：down 时所有 update 抛 DataAccessResourceFailureException。 */
final class FlakyJdbc extends JdbcTemplate {
    volatile boolean down;

    FlakyJdbc(DataSource ds) {
        super(ds);
    }

    @Override
    public int update(String sql, Object... args) {
        if (down) {
            throw new DataAccessResourceFailureException("模拟数据库不可用");
        }
        return super.update(sql, args);
    }
}
