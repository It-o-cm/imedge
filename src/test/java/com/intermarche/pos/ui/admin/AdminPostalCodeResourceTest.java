package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.store.PostalCode;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import com.intermarche.pos.ui.journal.JournalPage;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminPostalCodeResource}.
 * <p>
 * A Qute-backed CRUD over the {@link PostalCode} static finders, intercepted
 * with {@link org.mockito.Mockito#mockStatic}; the row written by a creation
 * has its {@code persist()} neutralized with
 * {@link org.mockito.Mockito#mockConstruction}. No Quarkus context and no
 * database is booted.
 * <p>
 * Branch enumeration (every arm exercised): {@code search} — no criterion, a
 * criterion matching the code, one matching the place, one matching neither,
 * and a row whose code or place is null; {@code save} — a missing code, a
 * missing place, a pair that already exists on ANOTHER row, the same pair on
 * the row being edited (which is not a clash), a creation and a correction;
 * {@code delete} — the unknown id, the unreadable id and the removal.
 */
class AdminPostalCodeResourceTest {

    /** The query the listing issues. */
    private static final String ORDERED = "order by code, place";

    /** The exact query the search issues for a non-blank fragment. */
    private static final String FILTERED =
            "(lower(code) like ?1 or lower(place) like ?1) order by code, place";

    /** The query {@code PostalCode.findPair} issues underneath. */
    private static final String PAIR = "code = ?1 and place = ?2";

    /**
     * Builds a resource with a mocked template.
     *
     * @return the wired resource
     */
    private AdminPostalCodeResource newResource() {
        AdminPostalCodeResource resource = new AdminPostalCodeResource();
        resource.adminPostalCodes = mock(Template.class);
        return resource;
    }

    /**
     * Builds a row.
     *
     * @param code the postal code, possibly null
     * @param place the place, possibly null
     * @return the row
     */
    private PostalCode row(String code, String place) {
        PostalCode postalCode = new PostalCode();
        postalCode.code = code;
        postalCode.place = place;
        return postalCode;
    }

    /**
     * Stubs the ordered listing.
     *
     * @param panache the active PanacheEntityBase mock
     * @param rows the rows to resolve
     */
    private void stubAll(MockedStatic<PanacheEntityBase> panache, List<PostalCode> rows) {
        // The mock is built BEFORE the stubbing expression: a mock created
        // inside when(...) is a nested stubbing, which Mockito refuses.
        PanacheQuery<PostalCode> query = pagedQuery(rows);
        panache.when(() -> PostalCode.find(ORDERED)).thenReturn(query);
    }

    /**
     * Stubs the filtered finder, which is the query the search builds for a
     * non-blank fragment.
     *
     * @param panache the active PanacheEntityBase mock
     * @param needle the lower-cased fragment the resource binds, wrapped in
     *        percent signs
     * @param rows the rows that query resolves to
     */
    private void stubFiltered(MockedStatic<PanacheEntityBase> panache, String needle,
            List<PostalCode> rows) {
        PanacheQuery<PostalCode> query = pagedQuery(rows);
        panache.when(() -> PostalCode.find(FILTERED, needle)).thenReturn(query);
    }

    /**
     * Builds a paged Panache query mock: it counts the rows it holds and hands
     * them back whichever page is asked for, which is what lets a test assert
     * the window arithmetic without a database.
     *
     * @param rows the rows the query resolves to
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<PostalCode> pagedQuery(List<PostalCode> rows) {
        PanacheQuery<PostalCode> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(rows);
        when(query.count()).thenReturn((long) rows.size());
        when(query.page(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(query);
        return query;
    }

    /**
     * Stubs the lookup {@code PostalCode.findPair} performs, which resolves to
     * the inherited {@code find} and not to a finder of its own.
     *
     * @param panache the active PanacheEntityBase mock
     * @param code the postal code asked for
     * @param place the place asked for
     * @param found the row to resolve, or null when that pair is unknown
     */
    @SuppressWarnings("unchecked")
    private void stubPair(MockedStatic<PanacheEntityBase> panache, String code, String place,
                          PostalCode found) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<PostalCode> query =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.firstResult()).thenReturn(found);
        panache.when(() -> PostalCode.find(PAIR, code, place)).thenReturn(query);
    }

    /**
     * With no criterion the unfiltered finder is used: a null and a blank
     * fragment are the same absence (both legs of the guard).
     */
    @Test
    void withoutACriterionTheUnfilteredFinderIsUsed() {
        AdminPostalCodeResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(row("75008", "Paris"), row("1234-567", "LISBOA")));
            assertEquals(2, resource.search(null).list().size());
            assertEquals(2, resource.search("   ").list().size());
            panache.verify(() -> PostalCode.find(ORDERED), times(2));
        }
    }

    /**
     * A criterion builds the filtered query, trimmed and lower-cased, and
     * wrapped in percent signs on both sides — the SQL form of the containment
     * the screen used to test in Java.
     */
    @Test
    void aCriterionBuildsTheFilteredQuery() {
        AdminPostalCodeResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFiltered(panache, "%lisboa%", List.of(row("1234-567", "LISBOA")));
            assertEquals(1, resource.search("  LISBOA  ").list().size());
            panache.verify(() -> PostalCode.find(FILTERED, "%lisboa%"), times(1));
        }
    }

    /**
     * The list page hands the template the window and the link prefix that
     * carries the criterion over to the next page.
     */
    @Test
    void theListPageEchoesTheCriterion() {
        AdminPostalCodeResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminPostalCodes.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFiltered(panache, "%75%", List.of(row("75008", "Paris")));
            assertSame(instance, resource.list("75", 1, null, true));
        }
        verify(instance).data("baseQuery", "text=75&");
        verify(instance).data("text", "75");
    }

    /**
     * A page number below one is clamped to the first page, and one beyond the
     * result to the last — both arms of the clamp.
     */
    @Test
    void thePageNumberIsClamped() {
        AdminPostalCodeResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminPostalCodes.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(row("75008", "Paris")));
            resource.list(null, 0, null, true);
            resource.list(null, 99, null, true);
        }
        org.mockito.ArgumentCaptor<Object> captor =
                org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(resource.adminPostalCodes, times(2))
                .data(org.mockito.ArgumentMatchers.eq("page"), captor.capture());
        assertEquals(1, ((JournalPage<?>) captor.getAllValues().get(0)).number);
        assertEquals(1, ((JournalPage<?>) captor.getAllValues().get(1)).number);
    }

    /**
     * A pair is not saved without both its halves: a code alone designates
     * nothing, a place alone is not reachable by a code.
     */
    @Test
    void anIncompletePairIsRefused() {
        AdminPostalCodeResource resource = newResource();
        MultivaluedMap<String, String> noPlace = new MultivaluedHashMap<>();
        noPlace.add("code", "75008");
        assertTrue(resource.save(noPlace).getLocation().toString().contains("noticeOk=false"));
        MultivaluedMap<String, String> noCode = new MultivaluedHashMap<>();
        noCode.add("place", "Paris");
        assertTrue(resource.save(noCode).getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * A pair already carried by ANOTHER row is refused: two rows saying the
     * same thing is a list nobody can maintain.
     */
    @Test
    void anExistingPairIsRefused() {
        AdminPostalCodeResource resource = newResource();
        PostalCode existing = row("75008", "Paris");
        existing.id = 7L;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("code", "75008");
        form.add("place", "Paris");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<PostalCode> construction = mockConstruction(PostalCode.class)) {
            stubPair(panache, "75008", "Paris", existing);
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            assertTrue(construction.constructed().isEmpty());
        }
    }

    /**
     * The SAME pair on the row being edited is not a clash: saving a row
     * without changing its pair must go through.
     */
    @Test
    void theSamePairOnTheEditedRowIsNotAClash() {
        AdminPostalCodeResource resource = newResource();
        PostalCode edited = row("75008", "Paris");
        edited.id = 7L;
        PostalCode stored = mock(PostalCode.class);
        stored.id = 7L;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "7");
        form.add("code", "75008");
        form.add("place", "Paris");
        form.add("country", "FR");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PostalCode.findById(7L)).thenReturn(stored);
            stubPair(panache, "75008", "Paris", edited);
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("FR", stored.country);
        verify(stored).persist();
    }

    /**
     * A pair nobody carries yet is created.
     */
    @Test
    void afreshPairIsCreated() {
        AdminPostalCodeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("code", "1234-567");
        form.add("place", "LISBOA");
        form.add("country", "PT");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<PostalCode> construction = mockConstruction(PostalCode.class)) {
            stubPair(panache, "1234-567", "LISBOA", null);
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals(1, construction.constructed().size());
            PostalCode built = construction.constructed().get(0);
            assertEquals("1234-567", built.code);
            assertEquals("LISBOA", built.place);
            assertEquals("PT", built.country);
            verify(built).persist();
        }
    }

    /**
     * A correction with an unreadable id creates rather than failing: the id is
     * the only thing that says « this existing row », and an unreadable one
     * says nothing.
     */
    @Test
    void anUnreadableIdCreates() {
        AdminPostalCodeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "pas un nombre");
        form.add("code", "75008");
        form.add("place", "Paris");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<PostalCode> construction = mockConstruction(PostalCode.class)) {
            stubPair(panache, "75008", "Paris", null);
            resource.save(form);
            assertEquals(1, construction.constructed().size());
        }
    }

    /**
     * Removing an unknown or unreadable id is refused and removes nothing.
     */
    @Test
    void removingAnUnknownRowIsRefused() {
        AdminPostalCodeResource resource = newResource();
        MultivaluedMap<String, String> unknown = new MultivaluedHashMap<>();
        unknown.add("id", "9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PostalCode.findById(9L)).thenReturn(null);
            assertTrue(resource.delete(unknown).getLocation().toString().contains("noticeOk=false"));
        }
        MultivaluedMap<String, String> unreadable = new MultivaluedHashMap<>();
        unreadable.add("id", "x");
        assertTrue(resource.delete(unreadable).getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * A known row is really removed: nothing references a postal code, so there
     * is nothing to deactivate around.
     */
    @Test
    void aknownRowIsRemoved() {
        AdminPostalCodeResource resource = newResource();
        PostalCode stored = mock(PostalCode.class);
        stored.code = "75008";
        stored.place = "Paris";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "7");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PostalCode.findById(7L)).thenReturn(stored);
            assertTrue(resource.delete(form).getLocation().toString().contains("noticeOk=true"));
        }
        verify(stored).delete();
        verify(stored, never()).persist();
    }

    /**
     * A non-null but blank fragment yields an empty base query: the isBlank leg
     * of the guard (distinct from the null leg) returns no carried criterion.
     */
    @Test
    void aBlankFragmentYieldsAnEmptyBaseQuery() {
        AdminPostalCodeResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminPostalCodes.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(row("75008", "Paris")));
            assertSame(instance, resource.list("   ", 1, null, true));
        }
        verify(instance).data("baseQuery", "");
    }

    /**
     * A pair that now belongs to a DIFFERENT row while editing is refused: the
     * clash exists and its id differs from the edited row's id (the true leg of
     * the identity guard).
     */
    @Test
    void aPairMovedToAnotherRowIsRefused() {
        AdminPostalCodeResource resource = newResource();
        PostalCode edited = mock(PostalCode.class);
        edited.id = 7L;
        PostalCode clash = row("75008", "Paris");
        clash.id = 9L;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "7");
        form.add("code", "75008");
        form.add("place", "Paris");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PostalCode.findById(7L)).thenReturn(edited);
            stubPair(panache, "75008", "Paris", clash);
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
        verify(edited, never()).persist();
    }

    /**
     * A blank id and a blank country are treated as absent: the isBlank leg of
     * longValue makes the save a creation, and the isBlank leg of blankToNull
     * drops the country to null.
     */
    @Test
    void aBlankIdAndBlankCountryAreTreatedAsAbsent() {
        AdminPostalCodeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "   ");
        form.add("code", "75008");
        form.add("place", "Paris");
        form.add("country", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<PostalCode> construction = mockConstruction(PostalCode.class)) {
            stubPair(panache, "75008", "Paris", null);
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals(1, construction.constructed().size());
            PostalCode built = construction.constructed().get(0);
            assertEquals("75008", built.code);
            assertEquals("Paris", built.place);
            assertEquals(null, built.country);
        }
    }
}
