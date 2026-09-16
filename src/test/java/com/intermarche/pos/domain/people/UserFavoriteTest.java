package com.intermarche.pos.domain.people;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link UserFavorite}.
 * <p>
 * The static helpers resolve to {@link PanacheEntityBase} under plain
 * {@code mvn test} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the pure checksum is exercised
 * directly. These methods carry no conditional branch — the coverage target
 * is met by exercising each once.
 */
class UserFavoriteTest {

    /**
     * Builds a favorite row.
     *
     * @param key the hidden feature key
     * @return the row
     */
    private UserFavorite favorite(String key) {
        UserFavorite favorite = new UserFavorite();
        favorite.employeeId = 7L;
        favorite.featureKey = key;
        return favorite;
    }

    /**
     * {@code hiddenKeys} projects the rows onto their feature keys.
     */
    @Test
    void hiddenKeysProjectsFeatureKeys() {
        List<UserFavorite> rows = List.of(favorite("settings"), favorite("store"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> UserFavorite.list("employeeId", 7L)).thenReturn(rows);
            Set<String> keys = UserFavorite.hiddenKeys(7L);
            assertEquals(Set.of("settings", "store"), keys);
        }
    }

    /**
     * {@code clearFor} returns the number of rows the delete removed.
     */
    @Test
    void clearForReturnsDeletedCount() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> UserFavorite.delete("employeeId", 7L)).thenReturn(2L);
            assertEquals(2L, UserFavorite.clearFor(7L));
        }
    }

    /**
     * {@code getChecksum} is deterministic for equal business content.
     */
    @Test
    void checksumIsDeterministic() {
        assertEquals(favorite("settings").getChecksum(), favorite("settings").getChecksum());
        assertTrue(favorite("settings").getChecksum() != favorite("store").getChecksum());
    }
}
