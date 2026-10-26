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
 * Unit tests for {@link StoreImageService}: the store node's echelon
 * resolution of the administered images and the register's reader of the
 * images the node distributed ({@code BO-03-03-15}, {@code BO-10-03-09}).
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
 * {@code resolvedImages} with and without a logo. On the register's road: a
 * logo distributed and none; a logo row whose picture is empty, which is not
 * a logo; a visual matched on the rule code, on the type, and on neither; a
 * visual row with no advantage code; and a null rule code and a null type.
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
            assertNull(service.resolvedReceiptLogo());
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
    /** BO-10-03-09: blank advantage codes and visuals scoped to another store never enter the resolved set. */
    @Test
    void unresolvedAndBlankAdvantageVisualsAreOmitted() {
        StoreImage blank = image("BLANK", null, null);
        blank.advantageCode = " ";
        StoreImage foreign = image("FOREIGN", EchelonLevel.PDV, "OTHER");
        foreign.advantageCode = "GIFT";
        try (var pdvs = mockStatic(Pdv.class);
             var images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE)).thenReturn(List.of(blank, foreign));
            assertTrue(service(null).advantageVisuals().isEmpty());
        }
    }

    /** BO-03-03-15: a missing PDV still resolves group defaults and a null candidate code cannot win a tie. */
    @Test
    void nullStoreAndCandidateCodesKeepTheStableDefault() {
        StoreImage first = image("DEFAULT", null, null);
        StoreImage nameless = image(null, null, null);
        assertEquals(first, service(null).closest(List.of(first, nameless)));
    }

    /** BO-03-03-15: a later, more distant candidate never displaces an already selected local image. */
    @Test
    void lessSpecificCandidateCannotReplaceTheLocalWinner() {
        StoreImage local = image("LOCAL", EchelonLevel.PDV, "00123");
        StoreImage group = image("GROUP", null, null);
        try (var pdvs = mockStatic(Pdv.class)) {
            assertEquals(local, service("00123").closest(List.of(local, group)));
        }
    }

    /**
     * Builds an image carrying a picture.
     *
     * @param code the image code
     * @param usage what it is for
     * @param advantageCode the advantage it illustrates, possibly null
     * @param picture the picture, possibly null
     * @return the image
     */
    private StoreImage image(String code, StoreImage.Usage usage, String advantageCode,
                             String picture) {
        StoreImage image = new StoreImage();
        image.code = code;
        image.label = code;
        image.usage = usage;
        image.advantageCode = advantageCode;
        image.imageData = picture;
        return image;
    }

    /**
     * BO-03-03-15: the distributed logo answers, and a row whose picture is
     * empty is skipped — a logo with no image is not a logo.
     */
    @Test
    void theDistributedLogoAnswers() {
        StoreImageService service = new StoreImageService();
        try (MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO))
                    .thenReturn(List.of(
                            image("VIDE", StoreImage.Usage.RECEIPT_LOGO, null, "  "),
                            image("LOGO", StoreImage.Usage.RECEIPT_LOGO, null, "data:png,A")));
            assertEquals("data:png,A", service.receiptLogo());
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO))
                    .thenReturn(List.of());
            assertEquals("", service.receiptLogo());
        }
    }

    /**
     * BO-10-07-13: the monetique and contactless logos travel with the
     * resolved set, each resolved on its own chain; a usage with no image
     * adds nothing.
     */
    @Test
    void theEftLogosTravelWithTheResolvedSet() {
        StoreImageService service = service("00123");
        StoreImage eft = image("EFT", null, null);
        eft.usage = StoreImage.Usage.EFT_LOGO;
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class);
             MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            tree(pdvs, enseignes);
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.EFT_LOGO))
                    .thenReturn(List.of(eft));
            assertEquals(List.of("EFT"), service.resolvedImages().stream().map(one -> one.code).toList());
        }
    }

    /**
     * BO-10-07-13: the distributed monetique and contactless logos answer,
     * and a usage nothing was distributed for answers nothing.
     */
    @Test
    void theDistributedEftLogosAnswer() {
        StoreImageService service = new StoreImageService();
        try (MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.EFT_LOGO))
                    .thenReturn(List.of(image("EFT", StoreImage.Usage.EFT_LOGO, null, "data:png,E")));
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.CONTACTLESS_LOGO))
                    .thenReturn(List.of(image("NFC", StoreImage.Usage.CONTACTLESS_LOGO, null, null)));
            assertEquals("data:png,E", service.eftLogo());
            assertEquals("", service.contactlessLogo());
        }
    }

    /**
     * BO-10-03-09: a visual is matched on the advantage's RULE code first
     * and on its TYPE next, so a shop illustrating one rule precisely beats
     * one illustrating the family; a rule and a type nothing names answer
     * nothing.
     */
    @Test
    void aVisualIsMatchedOnTheRuleThenOnTheType() {
        StoreImageService service = new StoreImageService();
        try (MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE))
                    .thenReturn(List.of(
                            image("SANS", StoreImage.Usage.LOYALTY_ADVANTAGE, null, "data:png,X"),
                            image("TYPE", StoreImage.Usage.LOYALTY_ADVANTAGE, "CADEAU",
                                    "data:png,T"),
                            image("REGLE", StoreImage.Usage.LOYALTY_ADVANTAGE, " fid-12 ",
                                    "data:png,R")));
            assertEquals("data:png,R", service.advantageVisual("FID-12", "CADEAU"));
            assertEquals("data:png,T", service.advantageVisual("FID-99", "CADEAU"));
            assertEquals("", service.advantageVisual("FID-99", "REMISE"));
            assertEquals("", service.advantageVisual(null, null));
            assertEquals("", service.advantageVisual("  ", "  "));
        }
    }

    /**
     * BO-10-03-09: a visual whose picture is empty does not answer, so the
     * advantage line prints alone rather than with a blank image directive.
     */
    @Test
    void aVisualWithoutAPictureDoesNotAnswer() {
        StoreImageService service = new StoreImageService();
        try (MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE))
                    .thenReturn(List.of(
                            image("CADEAU", StoreImage.Usage.LOYALTY_ADVANTAGE, "CADEAU", null)));
            assertEquals("", service.advantageVisual("CADEAU", "CADEAU"));
        }
    }

    /**
     * Nothing distributed at all answers the empty string on both roads, so
     * every caller falls back on what it printed before.
     */
    @Test
    void nothingDistributedAnswersNothing() {
        StoreImageService service = new StoreImageService();
        try (MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO))
                    .thenReturn(List.of());
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE))
                    .thenReturn(List.of());
            assertEquals("", service.receiptLogo());
            assertEquals("", service.advantageVisual("FID-12", "CADEAU"));
        }
    }
    /** BO-03-03-15/BO-10-03-09: missing logo pixels and blank matching visuals never produce an image directive. */
    @Test
    void imagesWithoutPixelsAndMissingListsYieldNoVisual() throws Exception {
        StoreImageService service = new StoreImageService();
        var logo = image("EMPTY", StoreImage.Usage.RECEIPT_LOGO, null, null);
        var visual = image("EMPTY", StoreImage.Usage.LOYALTY_ADVANTAGE, "GIFT", " ");
        try (var images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO)).thenReturn(List.of(logo));
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE)).thenReturn(List.of(visual));
            assertEquals("", service.receiptLogo());
            assertEquals("", service.advantageVisual("GIFT", " "));
        }
        var method = StoreImageService.class.getDeclaredMethod("match", List.class, String.class);
        method.setAccessible(true);
        assertEquals("", method.invoke(service, null, "GIFT"));
    }

    /**
     * BO-03-09-04: a logo chosen for the register replaces the logo of the
     * echelon chain; an unknown, inactive or non-logo choice changes nothing.
     */
    @Test
    void aChosenLogoReplacesTheResolvedOne() {
        StoreImageService service = service(null);
        StoreImage resolved = image("LOGO-FR", null, null);
        StoreImage chosen = image("LOGO-TPV", EchelonLevel.CHECKOUT_POINT, "C04");
        StoreImage off = image("LOGO-OFF", null, null);
        off.active = false;
        StoreImage visual = image("CADEAU", null, null);
        visual.usage = StoreImage.Usage.LOYALTY_ADVANTAGE;
        visual.advantageCode = "CADEAU";
        try (MockedStatic<StoreImage> images = mockStatic(StoreImage.class)) {
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO))
                    .thenReturn(List.of(resolved));
            images.when(() -> StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE))
                    .thenReturn(List.of(visual));
            images.when(() -> StoreImage.findByCode("LOGO-TPV")).thenReturn(chosen);
            images.when(() -> StoreImage.findByCode("LOGO-OFF")).thenReturn(off);
            images.when(() -> StoreImage.findByCode("CADEAU")).thenReturn(visual);
            assertEquals(List.of("LOGO-TPV", "CADEAU"),
                    service.resolvedImages("LOGO-TPV").stream().map(one -> one.code).toList());
            assertEquals(List.of("LOGO-FR", "CADEAU"),
                    service.resolvedImages(null).stream().map(one -> one.code).toList());
            assertEquals(List.of("LOGO-FR", "CADEAU"),
                    service.resolvedImages("LOGO-OFF").stream().map(one -> one.code).toList());
            assertEquals(List.of("LOGO-FR", "CADEAU"),
                    service.resolvedImages("CADEAU").stream().map(one -> one.code).toList());
        }
    }
}
