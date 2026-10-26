package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PostalCode}, the code/place pair used to fill an address
 * without typing the town.
 *
 * <p>The two static finders are Panache queries that resolve to
 * {@link PanacheEntityBase} under plain {@code mvn test}, so they are mocked with
 * {@link org.mockito.Mockito#mockStatic}. {@link PostalCode#getChecksum} carries no
 * branch and is exercised on rows differing by one field each so the hash is proven
 * field-sensitive, including the nullable country.
 */
class PostalCodeTest {

    /**
     * Builds a postal code with the given business fields, so each test states
     * exactly the fields it depends on.
     *
     * @param code    the postal code
     * @param place   the place it designates
     * @param country the ISO country, possibly null
     * @return the built postal code
     */
    private PostalCode postalCode(String code, String place, String country) {
        PostalCode postalCode = new PostalCode();
        postalCode.code = code;
        postalCode.place = place;
        postalCode.country = country;
        return postalCode;
    }

    /**
     * {@code findPair} runs the code-and-place query and returns its first result
     * verbatim when the pair is known.
     */
    @Test
    void findPairReturnsFirstMatchWhenKnown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PostalCode expected = postalCode("42000", "SAINT-ETIENNE", "FR");
            @SuppressWarnings("unchecked")
            PanacheQuery<PostalCode> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> PostalCode.find("code = ?1 and place = ?2", "42000", "SAINT-ETIENNE"))
                    .thenReturn(query);
            assertSame(expected, PostalCode.findPair("42000", "SAINT-ETIENNE"));
        }
    }

    /**
     * {@code findPair} returns null when the code/place pair is unknown.
     */
    @Test
    void findPairReturnsNullWhenUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<PostalCode> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> PostalCode.find("code = ?1 and place = ?2", "00000", "NOWHERE"))
                    .thenReturn(query);
            assertNull(PostalCode.findPair("00000", "NOWHERE"));
        }
    }

    /**
     * {@code findByCode} runs the place-ordered query for the code and returns its
     * result verbatim.
     */
    @Test
    void findByCodeRunsOrderedQuery() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            List<PostalCode> expected = List.of(postalCode("42000", "SAINT-ETIENNE", "FR"));
            panache.when(() -> PostalCode.list("code = ?1 order by place", "42000"))
                    .thenReturn(expected);
            assertSame(expected, PostalCode.findByCode("42000"));
        }
    }

    /**
     * {@code getChecksum} is stable for identical fields and changes when any
     * business field changes, including the nullable country.
     */
    @Test
    void checksumIsFieldSensitive() {
        PostalCode base = postalCode("42000", "SAINT-ETIENNE", "FR");
        assertEquals(base.getChecksum(), postalCode("42000", "SAINT-ETIENNE", "FR").getChecksum());
        assertNotEquals(base.getChecksum(), postalCode("42100", "SAINT-ETIENNE", "FR").getChecksum());
        assertNotEquals(base.getChecksum(), postalCode("42000", "ANDREZIEUX", "FR").getChecksum());
        assertNotEquals(base.getChecksum(), postalCode("42000", "SAINT-ETIENNE", null).getChecksum());
    }

    /** BO-03/08: the finder selects the required business scope and ordering, preserving the returned rows. */
    @org.junit.jupiter.api.Test
    void findByCodeUsesItsBusinessScopeAndOrder() {
        var row = new PostalCode();
        java.util.List<PostalCode> rows = java.util.List.of(row);
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("code = ?1 order by place", "75001")).thenReturn(rows);
            org.junit.jupiter.api.Assertions.assertSame(rows, PostalCode.findByCode("75001"));
            panache.verify(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("code = ?1 order by place", "75001"));
        }
    }

    /** BO-08-01-04: each synchronized business-field change invalidates the row checksum. */
    @org.junit.jupiter.api.Test
    void everyBusinessFieldChangeInvalidatesTheChecksum() {
        var row = new PostalCode();
        int previous = row.getChecksum();
        row.code = "75001";
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "code must participate in synchronization");
        previous = row.getChecksum();
        row.place = "Paris";
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "place must participate in synchronization");
        previous = row.getChecksum();
        row.country = "FR";
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "country must participate in synchronization");
        previous = row.getChecksum();
    }
}
