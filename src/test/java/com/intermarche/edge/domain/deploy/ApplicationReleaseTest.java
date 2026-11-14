package com.intermarche.edge.domain.deploy;

import com.intermarche.edge.service.deploy.AppVersion;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ApplicationRelease}. */
class ApplicationReleaseTest {

    /** BO-08-04-14: a healthy package passes its self-test, a defective one does not; the checksum is its SHA-256. */
    @Test
    void thePackageCarriesItsSelfTest() {
        ApplicationRelease release = new ApplicationRelease();
        release.product = ApplicationRelease.IMPOS;
        release.version = "2.0";
        assertEquals("product=IMPOS\nversion=2.0\nselftest=ok\n", release.packageContent());
        assertEquals(AppVersion.sha256(release.packageContent()), release.checksum());
        release.defective = true;
        assertEquals("product=IMPOS\nversion=2.0\nselftest=ko\n", release.packageContent());
    }

    /** The releases are found by product and version, and listed newest first per product. */
    @Test
    @SuppressWarnings("unchecked")
    void findersReadTheReleases() {
        ApplicationRelease row = new ApplicationRelease();
        List<Object> rows = List.of(row);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("product = ?1 and version = ?2", "IMPOS", "2.0"))
                    .thenReturn(query);
            panache.when(() -> PanacheEntityBase.list("order by product, createdAt desc, id desc")).thenReturn(rows);
            assertSame(row, ApplicationRelease.findRelease("IMPOS", "2.0"));
            assertSame(rows, ApplicationRelease.listOrdered());
        }
    }
}
