package com.intermarche.pos.ui.journal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JournalQuery}.
 * <p>
 * Branch enumeration (100%): {@code and} covers the first-condition arm
 * (no leading {@code and}) and the subsequent-condition arm (leading
 * {@code and}); {@code whereClause} covers the no-condition arm (empty string)
 * and the with-condition arm (leading {@code where}); {@code bind}/
 * {@code parameters} preserve insertion order.
 */
class JournalQueryTest {

    /**
     * An empty query yields no where clause and no parameters (no-condition
     * arm of {@code whereClause}).
     */
    @Test
    void emptyQueryHasNoWhereClause() {
        JournalQuery query = new JournalQuery();
        assertEquals("", query.whereClause());
        assertTrue(query.parameters().isEmpty());
    }

    /**
     * A single condition is not prefixed with {@code and} (first-condition
     * arm) and produces a leading {@code where} (with-condition arm).
     */
    @Test
    void singleConditionProducesWhere() {
        JournalQuery query = new JournalQuery();
        query.and("t.a = :a");
        query.bind("a", 1);
        assertEquals(" where t.a = :a", query.whereClause());
        assertEquals(1, query.parameters().get("a"));
    }

    /**
     * A second condition is joined with {@code and} (subsequent-condition arm)
     * and parameters keep insertion order.
     */
    @Test
    void secondConditionIsAnded() {
        JournalQuery query = new JournalQuery();
        query.and("t.a = :a");
        query.and("t.b = :b");
        query.bind("a", 1);
        query.bind("b", 2);
        assertEquals(" where t.a = :a and t.b = :b", query.whereClause());
        Map<String, Object> params = query.parameters();
        assertEquals(2, params.size());
        assertEquals("[a, b]", params.keySet().toString());
    }
}
