package com.antifraud.persistence.codegen;

import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;

public final class GenerateJooq {
    private GenerateJooq() {}

    public static void main(String[] args) throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:18")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("filesystem:" + args[0])
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword()); var statement = connection.createStatement();
                 var tables = statement.executeQuery("""
                         select to_regclass('public.transaction_audit') is not null
                            and to_regclass('public.transaction_label') is not null
                         """)) {
                tables.next();
                if (!tables.getBoolean(1)) {
                    throw new IllegalStateException("Flyway должен создать transaction_audit и transaction_label (задание 5.1)");
                }
            }

            GenerationTool.generate(new Configuration()
                    .withJdbc(new Jdbc().withDriver("org.postgresql.Driver")
                            .withUrl(postgres.getJdbcUrl())
                            .withUser(postgres.getUsername())
                            .withPassword(postgres.getPassword()))
                    .withGenerator(new Generator()
                            .withDatabase(new Database()
                                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                                    .withInputSchema("public")
                                    .withIncludes("transaction_audit|transaction_label"))
                            .withTarget(new Target()
                                    .withPackageName("com.antifraud.persistence.jooq")
                                    .withDirectory(args[1]))));
        }
    }
}
