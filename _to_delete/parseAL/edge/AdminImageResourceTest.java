package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.StoreImage;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminImageResource}, the image administration screen
 * ({@code BO-03-03-15}, {@code BO-10-03-09}).
 *
 * <p>No database and no Quarkus context: the custom finder of
 * {@link StoreImage} and the inherited {@code findById} are intercepted with
 * {@code mockStatic} on {@link StoreImage} and on {@link PanacheEntityBase}
 * respectively. The redirect's query string carries the outcome.
 *
 * <p>Branch enumeration: the two mandatory fields, each absent in turn; a
 * code already taken by ANOTHER image; an echelon named without its code; a
 * loyalty visual named without its advantage; a creation with no file; an
 * unreadable file; an unknown id on save and on delete; and the pure
 * helpers — the code normalisation, the usage fallback and the level
 * reader, the two echelons inside the store included.
 *
 * <p>The CREATING arm persists, so it is left to the end-to-end run.
 */
class AdminImageResourceTest {

    /**
     * Builds a resource whose resizer gives the picture back unchanged.
     *
     * @return the resource under test
     */
    private AdminImageResource resource() {
        AdminImageResource resource = new AdminImageResource();
        resource.imageResizeService = mock(ImageResizeService.class);
        when(resource.imageResizeService.resizeToDataUri("data:image/png;base64,AAA",
                AdminImageResource.MAX_DIMENSION)).thenReturn("data:image/png;base64,AAA");
        when(resource.imageResizeService.resizeToDataUri("illisible",
                AdminImageResource.MAX_DIMENSION))
                .thenThrow(new IllegalArgumentException("pas une image"));
        return resource;
    }

    /**
     * Builds a form carrying everything an import needs.
     *
     * @return the valid form
     */
    private MultivaluedMap<String, String> validForm() {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "logo-fr");
        form.putSingle("label", "Logo France");
        form.putSingle("usage", "RECEIPT_LOGO");
        form.putSingle("imgData", "data:image/png;base64,AAA");
        form.putSingle("active", "on");
        return form;
    }

    /**
     * Reads the notice the redirect carries.
     *
     * @param response the redirect
     * @return its query string, which carries the notice
     */
    private String notice(Response response) {
        return response.getLocation().getQuery();
    }

    /**
     * BO-03-03-15: code and label are the two an image cannot do without.
     */
    @Test
    void anImageNeedsItsCodeAndItsLabel() {
        for (String missing : new String[]{"code", "label"}) {
            MultivaluedMap<String, String> form = validForm();
            form.remove(missing);
            Response response = resource().save(form);
            assertTrue(notice(response).contains("obligatoires"), missing);
        }
    }

    /**
     * BO-03-03-15: a code already carried by ANOTHER image is refused.
     */
    @Test
    void aCodeIsTakenOnlyByAnotherImage() {
        StoreImage other = mock(StoreImage.class);
        other.id = 7L;
        try (MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findByCode("LOGO-FR")).thenReturn(other);
            assertTrue(notice(resource().save(validForm())).contains("pris"));
        }
    }

    /**
     * BO-03-03-15: an echelon without its code names nothing, and a loyalty
     * visual without its advantage illustrates nothing — both refused.
     */
    @Test
    void anEchelonAndAVisualNeedTheirCode() {
        try (MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findByCode("LOGO-FR")).thenReturn(null);
            MultivaluedMap<String, String> echelon = validForm();
            echelon.putSingle("level", "PDV");
            assertTrue(notice(resource().save(echelon)).contains("code de l'échelon"));
            MultivaluedMap<String, String> visual = validForm();
            visual.putSingle("usage", "LOYALTY_ADVANTAGE");
            assertTrue(notice(resource().save(visual)).contains("code de l'avantage"));
        }
    }

    /**
     * BO-03-03-15: a new image demands a file, and a file that is not an
     * image is refused rather than silently stored.
     */
    @Test
    void aNewImageNeedsAReadableFile() {
        try (MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findByCode("LOGO-FR")).thenReturn(null);
            MultivaluedMap<String, String> empty = validForm();
            empty.remove("imgData");
            assertTrue(notice(resource().save(empty)).contains("fichier"));
            MultivaluedMap<String, String> broken = validForm();
            broken.putSingle("imgData", "illisible");
            assertTrue(notice(resource().save(broken)).contains("fichier"));
        }
    }

    /**
     * An id nothing answers refuses, on the save as on the delete.
     */
    @Test
    void anUnknownIdRefusesBothGestures() {
        MultivaluedMap<String, String> saved = validForm();
        saved.putSingle("id", "9");
        MultivaluedMap<String, String> byId = new MultivaluedHashMap<>();
        byId.putSingle("id", "9");
        try (MockedStatic<StoreImage> images = mockStatic(StoreImage.class);
             MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            images.when(() -> StoreImage.findByCode("LOGO-FR")).thenReturn(null);
            base.when(() -> StoreImage.findById(9L)).thenReturn(null);
            AdminImageResource resource = resource();
            assertTrue(notice(resource.save(saved)).contains("inconnue"));
            assertTrue(notice(resource.delete(byId)).contains("inconnue"));
        }
    }

    /**
     * A posted code is trimmed, raised to upper case and cut to the column's
     * length; nothing typed answers null.
     */
    @Test
    void thePostedCodeIsNormalised() {
        assertNull(AdminImageResource.code(null));
        assertNull(AdminImageResource.code("   "));
        assertEquals("LOGO-FR", AdminImageResource.code("  logo-fr "));
        assertEquals(AdminImageResource.MAX_CODE,
                AdminImageResource.code("x".repeat(AdminImageResource.MAX_CODE + 3)).length());
    }

    /**
     * The usage reader answers what was posted, and the receipt logo for an
     * absent, a blank or an unknown value.
     */
    @Test
    void theUsageFallsBackOnTheLogo() {
        assertEquals(StoreImage.Usage.LOYALTY_ADVANTAGE,
                AdminImageResource.usage(" loyalty_advantage "));
        assertEquals(StoreImage.Usage.RECEIPT_LOGO, AdminImageResource.usage(null));
        assertEquals(StoreImage.Usage.RECEIPT_LOGO, AdminImageResource.usage("  "));
        assertEquals(StoreImage.Usage.RECEIPT_LOGO, AdminImageResource.usage("AUTRE"));
    }

    /**
     * BO-03-03-15: the three store echelons are read, and everything else —
     * absent, blank, unknown, or one of the two echelons inside the store —
     * answers the group's default.
     */
    @Test
    void onlyTheThreeStoreEchelonsAreRead() {
        assertEquals(EchelonLevel.COUNTRY, AdminImageResource.level(" country "));
        assertEquals(EchelonLevel.ENSEIGNE, AdminImageResource.level("ENSEIGNE"));
        assertEquals(EchelonLevel.PDV, AdminImageResource.level("pdv"));
        assertNull(AdminImageResource.level(null));
        assertNull(AdminImageResource.level("   "));
        assertNull(AdminImageResource.level("ISLAND"));
        assertNull(AdminImageResource.level("CHECKOUT_POINT"));
    }

    /**
     * The resizer's refusal is caught: an unreadable picture answers null and
     * the screen turns it into a refusal rather than a stack trace.
     */
    @Test
    void anUnreadablePictureAnswersNothing() {
        AdminImageResource resource = resource();
        assertNull(resource.resized(null));
        assertNull(resource.resized("   "));
        assertNull(resource.resized("illisible"));
        assertEquals("data:image/png;base64,AAA", resource.resized("data:image/png;base64,AAA"));
    }
}
