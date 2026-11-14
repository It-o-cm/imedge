package com.intermarche.edge.domain.people;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ArticleFieldRight} (BO-02-03-33).
 */
class ArticleFieldRightTest {

    /**
     * The listing is ordered by datum then profile, and the checksum hashes
     * both.
     */
    @Test
    void listingAndChecksum() {
        ArticleFieldRight right = new ArticleFieldRight();
        right.profileId = 4L;
        right.fieldCode = "VAT";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleFieldRight.list("order by fieldCode, profileId")).thenReturn(List.of(right));
            assertEquals(List.of(right), ArticleFieldRight.listAllOrdered());
        }
        assertEquals(Objects.hash(4L, "VAT"), right.getChecksum());
    }
}
