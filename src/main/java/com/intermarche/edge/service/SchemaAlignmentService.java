package com.intermarche.edge.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Brings the columns whose definition changed in place on a database the node
 * KEEPS from one start to the next.
 *
 * <p>A node that recreates its schema at each start (the dev profile) has
 * nothing to do here. A node that only UPDATES it (the demonstrator profile,
 * whose referential survives a restart) gets the new tables and columns from
 * Hibernate, but never a column whose type or precision changed: Hibernate's
 * update adds, it does not alter. Each statement below states the definition
 * the entities now declare, in the portable {@code SET DATA TYPE} form both H2
 * and PostgreSQL accept, and is idempotent — run on a column already aligned it
 * changes nothing.
 *
 * <p>Tolerant by construction: a statement the database refuses is logged and
 * skipped, in its own transaction, so it never stops the node from starting.
 */
@ApplicationScoped
public class SchemaAlignmentService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SchemaAlignmentService.class);

    /**
     * The alignments, one statement per changed column: the VAT rate of a
     * document line keeps four decimals, so a 5.5 % rate is not read 6 %
     * (LC-06-02-02, -07); an article code, and the code a sale line keeps,
     * hold up to 38 characters (BO-03-06-01, -10); a profile assignment names
     * a country, an enseigne or a point of sale on 60 (BO-01-03-04).
     */
    static final List<String> STATEMENTS = List.of(
            "ALTER TABLE customer_order_lines ALTER COLUMN vat_rate SET DATA TYPE NUMERIC(6,4)",
            "ALTER TABLE products ALTER COLUMN ean SET DATA TYPE VARCHAR(38)",
            "ALTER TABLE ticket_lines ALTER COLUMN ean SET DATA TYPE VARCHAR(38)",
            "ALTER TABLE ticket_lines ALTER COLUMN linked_article_ean SET DATA TYPE VARCHAR(38)",
            "ALTER TABLE employee_profiles ALTER COLUMN echelon_scope SET DATA TYPE VARCHAR(60)");

    /** How the schema is generated at start; only {@code update} keeps a database. */
    @ConfigProperty(name = "quarkus.hibernate-orm.database.generation", defaultValue = "none")
    String generation;

    /** Runs the alignment statements. */
    @Inject
    EntityManager entityManager;

    /**
     * Aligns the schema once the node has started.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        align();
    }

    /**
     * Runs every alignment statement when the schema is updated rather than
     * recreated.
     *
     * @return the number of statements the database applied
     */
    public int align() {
        LOGGER.info("Entering method align with generation: " + generation);
        if (!"update".equalsIgnoreCase(generation == null ? "" : generation.trim())) {
            LOGGER.info("Exiting method align (schema not kept, nothing to align)");
            return 0;
        }
        int applied = 0;
        for (String statement : STATEMENTS) {
            try {
                QuarkusTransaction.requiringNew().run(
                        () -> entityManager.createNativeQuery(statement).executeUpdate());
                applied++;
            } catch (RuntimeException e) {
                LOGGER.warnf("Alignement du schéma refusé (%s) : %s", statement, e.getMessage());
            }
        }
        LOGGER.info("Exiting method align (" + applied + " applied)");
        return applied;
    }
}
