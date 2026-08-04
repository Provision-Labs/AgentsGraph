package io.provisionlabs.agentsgraph.test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a classpath SQL script (e.g. a production {@code graphs.sql} deployment script or a test
 * fixture seeding {@code agentsgraph_graph_config}/{@code agentsgraph_processor}) against a
 * {@link DataSource} - typically an in-memory H2 standing in for the production database.
 *
 * <p>The splitter is quote-aware: {@code ;} and {@code --} inside single-quoted SQL string
 * literals (with the standard {@code ''} escape) are treated as data, not as statement
 * separators or comments - long seeded texts (prompt templates, graph JSON) may freely contain
 * both. {@code --} outside string literals starts a comment running to the end of the line.
 */
public final class SqlScriptRunner {

    private SqlScriptRunner() {
    }

    /** Reads {@code classpathResource} (e.g. {@code "/db/postgres/docscan_graph/graphs.sql"}) and executes it. */
    public static void run(DataSource dataSource, String classpathResource) {
        String sql;
        try (InputStream in = SqlScriptRunner.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing classpath resource: " + classpathResource);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + classpathResource, e);
        }
        runSql(dataSource, sql);
    }

    /** Executes the given raw SQL script text. */
    public static void runSql(DataSource dataSource, String sql) {
        List<String> statements = splitStatements(sql);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String part : statements) {
                statement.execute(part);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to execute SQL script", e);
        }
    }

    private static List<String> splitStatements(String sql) {
        // Посимвольный проход с учётом строковых литералов: прежний вариант делил по ';' и
        // отбрасывал '--' построчно, из-за чего ';' или '--' внутри seed-текстов (промпт-шаблоны,
        // JSON графа) ломали границы стейтментов.
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (inString) {
                current.append(ch);
                if (ch == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++; // экранированная кавычка '' - остаёмся в строке
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (ch == '\'') {
                inString = true;
                current.append(ch);
                continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++; // комментарий до конца строки
                }
                current.append('\n');
                continue;
            }
            if (ch == ';') {
                addIfNotBlank(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder current) {
        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
    }
}
