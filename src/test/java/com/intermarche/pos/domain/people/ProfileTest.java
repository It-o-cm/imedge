package com.intermarche.pos.domain.people;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Sort;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import com.intermarche.pos.domain.setting.Feature;

/**
 * Unit tests for {@link Profile}.
 * <p>
 * The pure grant lookups ({@code grant}, {@code allows}) are exercised on a
 * hand-built profile; the static finders resolve to {@link PanacheEntityBase}
 * under plain {@code mvn test} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every arm exercised): {@code grant} covers the loop-hit
 * arm and the loop-exhausted arm; {@code allows} covers the true and false
 * arms of the {@code != null} guard; {@code getChecksum} is deterministic.
 */
class ProfileTest {

    /**
     * Builds a profile holding the given grants.
     *
     * @param grants the grants to attach
     * @return the profile
     */
    private Profile profileWith(ProfileGrant... grants) {
        Profile profile = new Profile();
        profile.name = "Managers";
        profile.description = "desc";
        for (ProfileGrant grant : grants) {
            profile.grants.add(grant);
        }
        return profile;
    }

    /**
     * {@code grant} returns the matching grant when one is held (loop-hit arm).
     */
    @Test
    void grantReturnsMatchingGrant() {
        ProfileGrant held = new ProfileGrant("settings", CrudOption.VIEW, false, null);
        Profile profile = profileWith(held);
        assertSame(held, profile.grant(Feature.SETTINGS, CrudOption.VIEW));
    }

    /**
     * {@code grant} returns null when none matches (loop-exhausted arm).
     */
    @Test
    void grantReturnsNullWhenNoneMatches() {
        Profile profile = profileWith(new ProfileGrant("settings", CrudOption.VIEW, false, null));
        assertNull(profile.grant(Feature.SETTINGS, CrudOption.UPDATE));
    }

    /**
     * {@code allows} is true when a matching grant exists (non-null arm).
     */
    @Test
    void allowsTrueWhenHeld() {
        Profile profile = profileWith(new ProfileGrant("user", CrudOption.CREATE, false, null));
        assertTrue(profile.allows(Feature.USER, CrudOption.CREATE));
    }

    /**
     * {@code allows} is false when none matches (null arm).
     */
    @Test
    void allowsFalseWhenNotHeld() {
        Profile profile = profileWith();
        assertFalse(profile.allows(Feature.USER, CrudOption.CREATE));
    }

    /**
     * {@code findByName} returns the resolved row.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findByNameResolvesRow() {
        Profile expected = profileWith();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Profile> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Profile.find("name", "Managers")).thenReturn(query);
            assertSame(expected, Profile.findByName("Managers"));
        }
    }

    /**
     * {@code listAllOrdered} delegates to the sorted static listing.
     */
    @Test
    void listAllOrderedDelegatesToSortedListing() {
        List<Profile> expected = List.of(profileWith());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Profile.listAll(any(Sort.class))).thenReturn(expected);
            assertSame(expected, Profile.listAllOrdered());
        }
    }

    /**
     * {@code getChecksum} is deterministic for equal business content.
     */
    @Test
    void checksumIsDeterministic() {
        ProfileGrant grant = new ProfileGrant("settings", CrudOption.VIEW, false, null);
        assertEquals(profileWith(grant).getChecksum(), profileWith(grant).getChecksum());
    }
}
