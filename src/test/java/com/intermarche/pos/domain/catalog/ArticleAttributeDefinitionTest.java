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
 * Unit tests for {@link ArticleAttributeDefinition}, targeting 100% branch
 * coverage.
 * <p>
 * The static finders resolve the Panache {@code list}/{@code find} queries,
 * which under plain {@code mvn test} fall back to {@link PanacheEntityBase} and
 * are intercepted with {@link org.mockito.Mockito#mockStatic}. Each test is
 * fully isolated and asserts absolute expected values.
 */
class ArticleAttributeDefinitionTest {

    /**
     * listAllOrdered delegates to the code-ordered list finder and returns its
     * rows verbatim.
     */
    @Test
    void listAllOrderedDelegatesToOrderedFinder() {
        ArticleAttributeDefinition def = new ArticleAttributeDefinition();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleAttributeDefinition.list("order by code"))
                    .thenReturn(List.of(def));
            Assertions.assertEquals(List.of(def), ArticleAttributeDefinition.listAllOrdered());
        }
    }

    /**
     * findByCode delegates to the code finder and returns its first result.
     */
    @Test
    void findByCodeReturnsFirstResult() {
        ArticleAttributeDefinition def = new ArticleAttributeDefinition();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ArticleAttributeDefinition> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(def);
            panache.when(() -> ArticleAttributeDefinition.find("code", "ORGANIC")).thenReturn(query);
            Assertions.assertSame(def, ArticleAttributeDefinition.findByCode("ORGANIC"));
        }
    }

    /**
     * findByCode propagates a null first result when no definition carries the
     * code.
     */
    @Test
    void findByCodeReturnsNullWhenAbsent() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ArticleAttributeDefinition> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> ArticleAttributeDefinition.find("code", "GHOST")).thenReturn(query);
            Assertions.assertNull(ArticleAttributeDefinition.findByCode("GHOST"));
        }
    }

    /**
     * getChecksum combines the code and label into the expected Objects.hash
     * value.
     */
    @Test
    void getChecksumHashesCodeAndLabel() {
        ArticleAttributeDefinition def = new ArticleAttributeDefinition();
        def.code = "ORGANIC";
        def.label = "Issu de l'agriculture biologique";
        Assertions.assertEquals(Objects.hash("ORGANIC", "Issu de l'agriculture biologique"),
                def.getChecksum());
    }
}
