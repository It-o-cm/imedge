package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.StoreImage;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link StoreImageService}, the register's reader of the
 * images the node distributed ({@code BO-03-03-15}, {@code BO-10-03-09}).
 *
 * <p>The finder of {@link StoreImage} is a custom static and is intercepted
 * with {@code mockStatic}; nothing else is needed, the service holding no
 * other collaborator.
 *
 * <p>Branch enumeration (both arms): a logo distributed and none; a logo
 * row whose picture is empty, which is not a logo; a visual matched on the
 * rule code, on the type, and on neither; a visual row with no advantage
 * code; and a null rule code and a null type.
 */
class StoreImageServiceTest {

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
}
