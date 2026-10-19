package com.intermarche.edge.domain.setting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link StoreImage}, the administered images
 * ({@code BO-03-03-15}, {@code BO-10-03-09}).
 *
 * <p>Branch enumeration (both arms) of {@code closenessTo}: no echelon (the
 * group's default), each of the three store echelons matching and not
 * matching, the two echelons INSIDE the store which an image is never posed
 * at, a null echelon code on the image, a null code on the shop, and the
 * case and spacing of a match. {@code findByCode} is covered on its two
 * early returns, and the checksum on the field it must follow.
 */
class StoreImageTest {

    /**
     * Builds an image posed at an echelon.
     *
     * @param level the echelon, or null for the group's default
     * @param echelonCode the echelon code, possibly null
     * @return the image
     */
    private StoreImage posed(EchelonLevel level, String echelonCode) {
        StoreImage image = new StoreImage();
        image.code = "LOGO";
        image.label = "Logo";
        image.usage = StoreImage.Usage.RECEIPT_LOGO;
        image.level = level;
        image.echelonCode = echelonCode;
        return image;
    }

    /**
     * BO-03-03-15: an image posed at no echelon is the group's default and
     * applies to every shop.
     */
    @Test
    void theGroupDefaultAppliesEverywhere() {
        assertEquals(0, posed(null, null).closenessTo("00123", "ITM", "FR"));
        assertEquals(0, posed(null, null).closenessTo(null, null, null));
    }

    /**
     * BO-03-03-15: the three store echelons rank from the closest down, and
     * an echelon the shop does not belong to does not apply at all.
     */
    @Test
    void theClosestEchelonRanksHighest() {
        assertEquals(3, posed(EchelonLevel.PDV, "00123").closenessTo("00123", "ITM", "FR"));
        assertEquals(2, posed(EchelonLevel.ENSEIGNE, "ITM").closenessTo("00123", "ITM", "FR"));
        assertEquals(1, posed(EchelonLevel.COUNTRY, "FR").closenessTo("00123", "ITM", "FR"));
        assertEquals(-1, posed(EchelonLevel.PDV, "00999").closenessTo("00123", "ITM", "FR"));
        assertEquals(-1, posed(EchelonLevel.ENSEIGNE, "NETTO").closenessTo("00123", "ITM", "FR"));
        assertEquals(-1, posed(EchelonLevel.COUNTRY, "PT").closenessTo("00123", "ITM", "FR"));
    }

    /**
     * BO-03-03-15: a logo is the paper of a shop and not of a lane, so the
     * two echelons inside the store never apply.
     */
    @Test
    void theEchelonsInsideTheStoreNeverApply() {
        assertEquals(-1, posed(EchelonLevel.ISLAND, "I1").closenessTo("00123", "ITM", "FR"));
        assertEquals(-1,
                posed(EchelonLevel.CHECKOUT_POINT, "CAISSE1").closenessTo("00123", "ITM", "FR"));
    }

    /**
     * A match ignores case and spacing; a missing code on either side is no
     * match rather than a wildcard.
     */
    @Test
    void aMatchNeedsBothCodes() {
        assertEquals(2, posed(EchelonLevel.ENSEIGNE, " itm ").closenessTo("00123", "ITM", "FR"));
        assertEquals(-1, posed(EchelonLevel.ENSEIGNE, null).closenessTo("00123", "ITM", "FR"));
        assertEquals(-1, posed(EchelonLevel.ENSEIGNE, "ITM").closenessTo("00123", null, "FR"));
    }

    /**
     * A code nobody typed is looked up nowhere: the finder answers null
     * without touching the database.
     */
    @Test
    void findByCodeAnswersNullForNothing() {
        assertNull(StoreImage.findByCode(null));
        assertNull(StoreImage.findByCode("   "));
    }

    /**
     * Every usage names itself for the administration screen.
     */
    @Test
    void everyUsageCarriesAScreenLabel() {
        for (StoreImage.Usage usage : StoreImage.Usage.values()) {
            assertFalse(usage.getLabel() == null || usage.getLabel().isBlank());
        }
        assertEquals("Logo de ticket", StoreImage.Usage.RECEIPT_LOGO.getLabel());
    }

    /**
     * The checksum follows the PICTURE too: replacing the image under an
     * unchanged name is the change the distribution must see.
     */
    @Test
    void theChecksumFollowsThePicture() {
        StoreImage first = posed(EchelonLevel.PDV, "00123");
        StoreImage second = posed(EchelonLevel.PDV, "00123");
        first.imageData = "data:image/png;base64,AAA";
        second.imageData = "data:image/png;base64,AAA";
        assertEquals(first.getChecksum(), second.getChecksum());
        second.imageData = "data:image/png;base64,BBB";
        assertNotEquals(first.getChecksum(), second.getChecksum());
    }
}
