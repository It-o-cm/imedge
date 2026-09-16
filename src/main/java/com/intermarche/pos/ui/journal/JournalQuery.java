package com.intermarche.pos.ui.journal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A built JPQL query fragment: the {@code where} clause (leading with a space,
 * or empty when no criterion applies) together with the named parameters it
 * binds. Produced by the pure builders of {@link JournalService} and consumed
 * both to list rows and to count them, so the two never drift apart.
 * <p>
 * A value object with no behaviour beyond accumulation: keeping the query
 * assembly a plain String/Map pair is what lets every criterion be verified by
 * a pure unit test, with no {@code EntityManager} in sight.
 */
public class JournalQuery {

    /** The accumulated {@code where} conditions (without the {@code where} keyword). */
    private final StringBuilder conditions = new StringBuilder();

    /** The named parameters bound by the conditions, in insertion order. */
    private final Map<String, Object> parameters = new LinkedHashMap<>();

    /**
     * Appends a condition, ANDing it with the ones already accumulated, and
     * binds its parameters.
     *
     * @param condition the JPQL boolean condition
     */
    public void and(String condition) {
        if (conditions.length() > 0) {
            conditions.append(" and ");
        }
        conditions.append(condition);
    }

    /**
     * Binds a named parameter.
     *
     * @param name the parameter name (without the colon)
     * @param value the bound value
     */
    public void bind(String name, Object value) {
        parameters.put(name, value);
    }

    /**
     * Returns the {@code where} clause, leading with {@code " where "} when at
     * least one condition was accumulated, or the empty string otherwise.
     *
     * @return the where clause fragment
     */
    public String whereClause() {
        return conditions.length() == 0 ? "" : " where " + conditions;
    }

    /**
     * Returns the bound parameters in insertion order.
     *
     * @return the parameter map
     */
    public Map<String, Object> parameters() {
        return parameters;
    }
}
