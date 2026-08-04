package io.provisionlabs.agentsgraph.test;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScriptRunnerTest {

    private static JdbcDataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:SqlScriptRunnerTest_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }

    @Test
    void semicolonsAndCommentsInsideStringLiteralsAreData() throws Exception {
        // Форма реального бага: seed-текст (промпт-шаблон) содержит ';' и '--' внутри
        // строкового литерала - прежний сплиттер рвал на них стейтмент.
        JdbcDataSource ds = h2();
        SqlScriptRunner.runSql(ds, """
                create table t (id bigint primary key, txt text);
                -- обычный комментарий; с точкой с запятой
                insert into t values (1, 'a; b -- not a comment; still text');
                insert into t values (2, 'квоты '' внутри; и снова -- текст'); -- хвостовой комментарий
                """);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select txt from t order by id")) {
            r.next();
            assertThat(r.getString(1)).isEqualTo("a; b -- not a comment; still text");
            r.next();
            assertThat(r.getString(1)).isEqualTo("квоты ' внутри; и снова -- текст");
        }
    }

    @Test
    void commentOnlyScriptExecutesNothing() {
        SqlScriptRunner.runSql(h2(), "-- только комментарий; ничего больше\n");
    }
}
