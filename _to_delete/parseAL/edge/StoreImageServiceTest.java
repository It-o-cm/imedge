package com.intermarche.edge.service;

import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.StoreImage;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StoreImageService}, the echelon resolution of the
 * administered images ({@code BO-03-03-15}, {@code BO-10-03-09}).
 *
 * <p>The organisation tree is intercepted with {@code mockStatic} on
 * {@link Pdv} and {@link Enseigne} — both finders are declared on their own
 * entity — and the image finder on {@link StoreImage}. No database is
 * booted.
 *
 * <p>Branch enumeration (both arms): the four echelons competing and the
 * closest winning; a candidate posed somewhere else entirely; a tie settled
 * by the code; no candidate at all; a node with no PDV number; a PDV the
 * tree does not know; a PDV with no enseigne; an enseigne the tree does not
 * know; a visual with no advantage code; and the composition of
 * {@code resolvedImages} with and without a logo.
 */
class StoreImageServiceTest {

    /**
     * Builds a service whose node identity is the given PDV number.
     *
     * @param pdvNumber the node's own PDV number
     * @return the service under test
     */
    private StoreImageService service(String pdvNumber) {
        StoreImageService service = new StoreImageService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.pdvNumber()).thenReturn(pdvNumber);
        return service;
    }

    /**
     * Builds an image posed at an echelon.
     *
     * @param code the image code
     * @param level the echelon, or null for the group's default
     * @param echelonCode the echelon code, possibly null
     * @return the image
     */
    private StoreImage image(String code, EchelonLevel level, String echelonCode) {
        StoreImage image = new StoreImage();
        image.code = code;
        image.label = code;
        image.usage = StoreImage.Usage.RECEIPT_LOGO;
        image.level = level;
        image.echelonCode = echelonCode;
        image.imageData = "data:image/png;base64,AAA";
        return image;
    }

    /**
     * Stubs the organisation tree: the PDV belongs to an enseigne, which
     * belongs to a country.
     *
     * @param pdvStatic the PDV static mock
     * @param enseigneStatic the enseigne static mock
     */
    private void tree(MockedStatic<Pdv> pdvStatic, MockedStatic<Enseigne> enseigneStatic) {
        Pdv pdv = mock(Pdv.class);
        pdv.enseigneCode = "ITM";
        Enseigne enseigne = mock(Enseigne.class);
        enseigne.countryCode = "FR";
        pdvStatic.when(() -> Pdv.findByNumber("00123")).thenReturn(pdv);
        enseigneStatic.when(() -> Enseigne.findByCode("ITM")).thenReturn(enseigne);
    }

    /**
     * BO-03-03-15: the four echelons compete and the PDV's logo wins.
     */
    @Test
    void theClosestEchelonWins() {
        StoreImageService service = service("00123");
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class)) {
            tree(pdvs, enseignes);
            assertEquals("PDV", service.closest(List.of(
                    image("GROUPE", null, null),
                    image("PAYS", EchelonLevel.COUNTRY, "FR"),
                    image("ENSEIGNE", EchelonLevel.ENSEIGNE, "ITM"),
                    image("PDV", EchelonLevel.PDV, "00123"))).code);
        }
    }

    /**
     * BO-03-03-15: with no logo on the shop's own PDV, its enseigne answers;
     * with none there either, its country; with none there either, the
     * group's default. An image posed on another shop never answers.
     */
    @Test
    void theChainFallsBackEchelonByEchelon() {
        StoreImageService service = service("00123");
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class)) {
            tree(pdvs, enseignes);
            StoreImage elsewhere = image("AUTRE", EchelonLevel.PDV, "00999");
            assertEquals("ENSEIGNE", service.closest(List.of(elsewhere,
                    image("GROUPE", null, null),
                    image("PAYS", EchelonLevel.COUNTRY, "FR"),
                    image("ENSEIGNE", EchelonLevel.ENSEIGNE, "ITM"))).code);
            assertEquals("PAYS", service.closest(List.of(elsewhere,
                    image("GROUPE", null, null),
                    image("PAYS", EchelonLevel.COUNTRY, "FR"))).code);
            assertEquals("GROUPE", service.closest(List.of(elsewhere,
                    image("GROUPE", null, null))).code);
            assertNull(service.closest(List.of(elsewhere)));
        }
    }

    /**
     * Two images at the SAME echelon are settled by the code, so the choice
     * does not depend on the order the rows came back in.
     */
    @Test
    void aTieIsSettledByTheCode() {
        StoreImageService service = service("00123");
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class)) {
            tree(pdvs, enseignes);
            assertEquals("A", service.closest(List.of(
                    image("B", EchelonLevel.PDV, "00123"),
                    image("A", EchelonLevel.PDV, "00123"))).code);
            assertEquals("A", service.closest(List.of(
                    image("A", EchelonLevel.PDV, "00123"),
                    image("B", EchelonLevel.PDV, "00123"))).code);
        }
    }

    /**
     * No candidate at all — null or empty — answers nothing rather than
     * failing.
     */
    @Test
    void noCandidateAnswersNothing() {
        StoreImageService service = service("00123");
        assertNull(service.closest(null));
        assertNull(service.closest(List.of()));
    }

    /**
     * A node that was never commissioned, one whose PDV the tree does not
     * know, one whose PDV hangs under no enseigne and one whose enseigne the
     * tree does not know all keep the group's default — and nothing else.
     */
    @Test
    void anIncompleteTreeKeepsTheGroupDefault() {
        List<StoreImage> candidates = List.of(
                image("GROUPE", null, null),
                image("PAYS", EchelonLevel.COUNTRY, "FR"),
                image("ENSEIGNE", EchelonLevel.ENSEIGNE, "ITM"));
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class)) {
            assertEquals("GROUPE", service("").closest(candidates).code);
            pdvs.when(() -> Pdv.findByNumber("00123")).thenReturn(null);
            assertEquals("GROUPE", service("00123").closest(candidates).code);
            Pdv orphan = mock(Pdv.class);
            orphan.enseigneCode = null;
            pdvs.when(() -> Pdv.findByNumber("00124")).thenReturn(orphan);
            assertEquals("GROUPE", service("00124").closest(candidates).code);
            Pdv attached = mock(Pdv.class);
            attached.enseigneCode = "ITM";
            pdvs.when(() -> Pdv.findByNumber("00125")).thenReturn(attached);
            enseignes.when(() -> Enseigne.findByCode("ITM")).thenReturn(null);
            assertEquals("ENSEIGNE", service("00125").closest(candidates).code);
        }
    }

    /**
     * BO-10-03-09: one visual per advantage, each resolved on its own chain;
     * a visual naming no advantage is not a visual and is left out.
     */
    @Test
    void oneVisualPerAdvantage() {
        StoreImageService service = service("00123");
        StoreImage group = image("CADEAU-GROUPE", null, null);
        group.usage = StoreImage.Usage.LOYALTY_ADVANTAGE;
        group.advantageCode = "CADEAU";
        StoreImage shop = image("CADEAU-PDV", EchelonLevel.PDV, "00123");
        shop.usage = StoreImage.Usage.LOYALTY_ADVANTAGE;
        shop.advantageCode = "CADEAU";
        StoreImage other = image("FREQ", null, null);
        other.usage = StoreImage.Usage.LOYALTY_ADVANTAGE;
        other.advantageCode = "FREQUENTATION";
        StoreImage nameless = image("SANS", null, null);
        nameless.usage = StoreImage.Usage.LOYALTY_ADVANTAGE;
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class);
             MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            tree(pdvs, enseignes);
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE))
                    .thenReturn(List.of(group, shop, other, nameless));
            Map<String, StoreImage> resolved = service.advantageVisuals();
            assertEquals(2, resolved.size());
            assertEquals("CADEAU-PDV", resolved.get("CADEAU").code);
            assertEquals("FREQ", resolved.get("FREQUENTATION").code);
        }
    }

    /**
     * BO-03-03-15: what the distribution hands over is the resolved logo
     * followed by the resolved visuals, and a store with no logo hands over
     * the visuals alone.
     */
    @Test
    void theDistributionCarriesTheResolvedSet() {
        StoreImageService service = service("00123");
        StoreImage logo = image("LOGO-FR", EchelonLevel.COUNTRY, "FR");
        StoreImage visual = image("CADEAU", null, null);
        visual.usage = StoreImage.Usage.LOYALTY_ADVANTAGE;
        visual.advantageCode = "CADEAU";
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class);
             MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            tree(pdvs, enseignes);
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO))
                    .thenReturn(List.of(logo));
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE))
                    .thenReturn(List.of(visual));
            List<StoreImage> resolved = service.resolvedImages();
            assertEquals(List.of("LOGO-FR", "CADEAU"),
                    resolved.stream().map(one -> one.code).toList());
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO))
                    .thenReturn(List.of());
            assertNull(service.receiptLogo());
            assertEquals(1, service.resolvedImages().size());
        }
    }

    /**
     * A store with nothing administered at all hands over nothing.
     */
    @Test
    void nothingAdministeredCarriesNothing() {
        StoreImageService service = service("00123");
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class);
             MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            tree(pdvs, enseignes);
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO))
                    .thenReturn(List.of());
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE))
                    .thenReturn(List.of());
            assertTrue(service.resolvedImages().isEmpty());
            assertTrue(service.advantageVisuals().isEmpty());
        }
    }
}
