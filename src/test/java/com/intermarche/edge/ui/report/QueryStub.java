package com.intermarche.edge.ui.report;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mocked {@link EntityManager} for the report tests: each query it creates
 * answers the rows registered for the first fragment its JPQL contains, an
 * empty list otherwise, and every query issued is kept with its parameters so
 * a test can assert what the report asked.
 */
final class QueryStub {

    /** The rows by JPQL fragment, in registration order. */
    private final Map<String, List<?>> results = new LinkedHashMap<>();

    /** Every JPQL issued, in order. */
    final List<String> issued = new ArrayList<>();

    /** Every parameter bound, by name, the last value winning. */
    final Map<String, Object> parameters = new LinkedHashMap<>();

    /** The mocked persistence session. */
    final EntityManager entityManager = mock(EntityManager.class);

    /**
     * Builds a stub answering nothing yet.
     */
    @SuppressWarnings("unchecked")
    QueryStub() {
        when(entityManager.createQuery(anyString(), any(Class.class))).thenAnswer(call -> {
            String jpql = call.getArgument(0);
            issued.add(jpql);
            TypedQuery<Object> query = mock(TypedQuery.class);
            when(query.setParameter(anyString(), any())).thenAnswer(bind -> {
                parameters.put(bind.getArgument(0), bind.getArgument(1));
                return query;
            });
            List<?> rows = List.of();
            for (Map.Entry<String, List<?>> entry : results.entrySet()) {
                if (jpql.contains(entry.getKey())) {
                    rows = entry.getValue();
                    break;
                }
            }
            when(query.getResultList()).thenReturn((List<Object>) rows);
            return query;
        });
    }

    /**
     * Registers the rows the queries containing a fragment answer.
     *
     * @param fragment the JPQL fragment
     * @param rows the rows
     * @return this stub
     */
    QueryStub answer(String fragment, List<?> rows) {
        results.put(fragment, rows);
        return this;
    }

    /**
     * Builds an {@code Object[]} row.
     *
     * @param cells the cells
     * @return the row
     */
    static Object[] row(Object... cells) {
        return cells;
    }

    /**
     * Wraps rows into a list of {@code Object[]}.
     *
     * @param rows the rows
     * @return the list
     */
    static List<Object[]> rows(Object[]... rows) {
        return new ArrayList<>(List.of(rows));
    }

    /**
     * Whether a query containing a fragment was issued.
     *
     * @param fragment the fragment
     * @return true when one was
     */
    boolean asked(String fragment) {
        for (String jpql : issued) {
            if (jpql.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
