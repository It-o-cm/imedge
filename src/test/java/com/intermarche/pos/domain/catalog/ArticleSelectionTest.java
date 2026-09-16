package com.intermarche.pos.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ArticleSelection}, targeting 100% branch coverage.
 * <p>
 * The static finders resolve the Panache {@code list}/{@code find} queries,
 * which under plain {@code mvn test} fall back to {@link PanacheEntityBase} and
 * are intercepted with {@link org.mockito.Mockito#mockStatic}. Each test is
 * fully isolated and asserts absolute expected values.
 */
class ArticleSelectionTest {

    /**
     * listAllOrdered delegates to the name-ordered list finder and returns its
     * rows verbatim.
     */
    @Test
    void listAllOrderedDelegatesToOrderedFinder() {
        ArticleSelection selection = new ArticleSelection();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.list("order by name"))
                    .thenReturn(List.of(selection));
            Assertions.assertEquals(List.of(selection), ArticleSelection.listAllOrdered());
        }
    }

    /**
     * findByName delegates to the name finder and returns its first result.
     */
    @Test
    void findByNameReturnsFirstResult() {
        ArticleSelection selection = new ArticleSelection();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ArticleSelection> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(selection);
            panache.when(() -> ArticleSelection.find("name", "Épicerie")).thenReturn(query);
            Assertions.assertSame(selection, ArticleSelection.findByName("Épicerie"));
        }
    }

    /**
     * findByName propagates a null first result when no selection carries the
     * name.
     */
    @Test
    void findByNameReturnsNullWhenAbsent() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ArticleSelection> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> ArticleSelection.find("name", "Ghost")).thenReturn(query);
            Assertions.assertNull(ArticleSelection.findByName("Ghost"));
        }
    }

    /**
     * getChecksum combines the name and criteria into the expected Objects.hash
     * value.
     */
    @Test
    void getChecksumHashesNameAndCriteria() {
        ArticleSelection selection = new ArticleSelection();
        selection.name = "Épicerie";
        selection.criteria = "type=UNIT&status=ACTIVE";
        Assertions.assertEquals(Objects.hash("Épicerie", "type=UNIT&status=ACTIVE"),
                selection.getChecksum());
    }
}
