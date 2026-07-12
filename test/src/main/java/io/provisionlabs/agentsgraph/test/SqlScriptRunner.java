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
 * <p>Full-line {@code --} comments are stripped <em>before</em> statements are split on {@code ;},
 * so semicolons inside comments don't break statement boundaries. Statement bodies (e.g. graph
 * JSON inside an {@code INSERT}) must not contain semicolons - AgentsGraph's own graph JSON
 * dialect never needs one.
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
        // Strip full-line comments first, so a ';' inside a comment can't split a statement.
        String withoutComments = sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce((a, b) -> a + "\n" + b).orElse("");

        List<String> statements = new ArrayList<>();
        for (String part : withoutComments.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }
}
