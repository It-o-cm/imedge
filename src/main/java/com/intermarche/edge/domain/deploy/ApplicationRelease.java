package com.intermarche.edge.domain.deploy;

import com.intermarche.edge.service.deploy.AppVersion;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A PUBLISHED VERSION of one application of the suite, the thing a
 * deployment lot distributes (BO-08-03-05, BO-08-03-06, BO-08-03-09/10).
 *
 * <p>Two products: IMEDGE (the store node and the central brick) and IMPOS
 * (the register). A release is a version, its kind (major, minor, patch) and
 * its PACKAGE: the content a node downloads and verifies against the
 * checksum this row computes. A release declared DEFECTIVE ships a package
 * whose self-test fails — what the demonstration uses to show the automatic
 * rollback (BO-08-04-14) without breaking anything real.
 */
@Entity
@Table(name = "application_release")
public class ApplicationRelease extends PanacheEntity {

    /** The store node and central brick application. */
    public static final String IMEDGE = "IMEDGE";

    /** The register application. */
    public static final String IMPOS = "IMPOS";

    /** IMEDGE or IMPOS. */
    @Column(name = "product", nullable = false, length = 10)
    public String product;

    /** The version. */
    @Column(name = "version", nullable = false, length = 40)
    public String version;

    /** MAJOR, MINOR or PATCH. */
    @Column(name = "release_kind", nullable = false, length = 8)
    public String releaseKind;

    /** What the version brings. */
    @Column(name = "notes", length = 300)
    public String notes;

    /** Whether the package fails its self-test (demonstration of the rollback). */
    @Column(name = "defective", nullable = false)
    public boolean defective;

    /** When it was published. */
    @Column(name = "created_at")
    public LocalDateTime createdAt;

    /**
     * The package a node downloads for this release.
     *
     * @return the package content
     */
    public String packageContent() {
        return "product=" + product + "\nversion=" + version + "\nselftest=" + (defective ? "ko" : "ok") + "\n";
    }

    /**
     * The checksum the deployment announces for this release.
     *
     * @return the SHA-256 of the package
     */
    public String checksum() {
        return AppVersion.sha256(packageContent());
    }

    /**
     * Finds a release.
     *
     * @param product IMEDGE or IMPOS
     * @param version the version
     * @return the release, or null
     */
    public static ApplicationRelease findRelease(String product, String version) {
        return find("product = ?1 and version = ?2", product, version).firstResult();
    }

    /**
     * Every release, by product then publication, newest first.
     *
     * @return the releases
     */
    public static List<ApplicationRelease> listOrdered() {
        return list("order by product, createdAt desc, id desc");
    }
}
