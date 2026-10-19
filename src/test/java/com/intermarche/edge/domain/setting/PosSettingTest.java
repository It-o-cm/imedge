package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PosSetting}.
 * <p>
 * The entity is a Panache active record holding only an overridden key/value
 * pair; its single static finder resolves to {@link PanacheEntityBase} under
 * plain {@code mvn test} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic} inside a try-with-resources block. No
 * database and no Quarkus context is booted. The finder carries no branch, so
 * coverage is completed by exercising the found and not-found results and the
 * read/write of both public fields. Each test is fully isolated and asserts
 * absolute expected values.
 */
class PosSettingTest {

    /**
     * The two public fields accept and return their assigned values.
     */
    @Test
    void fieldsAreReadWrite() {
        PosSetting setting = new PosSetting();
        setting.settingKey = "display.show-ean";
        setting.settingValue = "true";
        Assertions.assertEquals("display.show-ean", setting.settingKey);
        Assertions.assertEquals("true", setting.settingValue);
    }

    /**
     * findByKey queries the row carrying the given catalog key and returns the
     * finder's first result.
     */
    @Test
    void findByKeyReturnsTheQueriedRow() {
        PosSetting expected = new PosSetting();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<PosSetting> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> PosSetting.find("settingKey", "display.show-ean")).thenReturn(query);
            Assertions.assertSame(expected, PosSetting.findByKey("display.show-ean"));
        }
    }

    /**
     * findByKey propagates a null first result when no row overrides the default.
     */
    @Test
    void findByKeyReturnsNullWhenTheDefaultApplies() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<PosSetting> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> PosSetting.find("settingKey", "unknown.key")).thenReturn(query);
            Assertions.assertNull(PosSetting.findByKey("unknown.key"));
        }
    }
}
