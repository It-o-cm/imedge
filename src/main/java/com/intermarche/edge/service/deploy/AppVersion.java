package com.intermarche.edge.service.deploy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Compares APPLICATION VERSIONS and fingerprints update packages
 * (BO-08-03-01, BO-08-03-07).
 *
 * <p>A version is dotted numbers optionally followed by a qualifier
 * ({@code 1.4.2}, {@code 1.0-SNAPSHOT}). The numbers are compared segment by
 * segment, a missing segment counting as zero ({@code 1.4} equals
 * {@code 1.4.0}); at equal numbers a qualified version ranks BELOW the plain
 * one, so a {@code 1.0-SNAPSHOT} built on a desk is lower than the {@code 1.0}
 * a deployment lot publishes. A segment that is not a number counts as zero
 * rather than failing: a version typed badly must not stop a node from
 * comparing the next one.
 */
public final class AppVersion {

    /** Utility class: no instance. */
    private AppVersion() {
    }

    /**
     * Compares two versions.
     *
     * @param left the first version, possibly null
     * @param right the second version, possibly null
     * @return negative when left is lower, zero when equal, positive when higher
     */
    public static int compare(String left, String right) {
        int[] a = numbers(left);
        int[] b = numbers(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return Boolean.compare(!qualified(left), !qualified(right));
    }

    /**
     * Tells whether a node running a version must update to a target.
     *
     * @param installed the version the node runs
     * @param target the version it is asked to run
     * @return true when the installed version is strictly lower
     */
    public static boolean isLower(String installed, String target) {
        return compare(installed, target) < 0;
    }

    /**
     * Reads the numeric segments of a version.
     *
     * @param version the version, possibly null
     * @return its segments, a non-numeric one read as zero
     */
    static int[] numbers(String version) {
        String core = version == null ? "" : version.trim().split("-", 2)[0];
        String[] parts = core.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            numbers[i] = parts[i].matches("\\d{1,9}") ? Integer.parseInt(parts[i]) : 0;
        }
        return numbers;
    }

    /**
     * Tells whether a version carries a qualifier.
     *
     * @param version the version, possibly null
     * @return true when a dash follows the numbers
     */
    static boolean qualified(String version) {
        return version != null && version.contains("-");
    }

    /**
     * Fingerprints a package the way the deployment announces it.
     *
     * @param content the package content
     * @return the SHA-256 of its UTF-8 bytes, in lower-case hexadecimal
     */
    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 indisponible", unavailable);
        }
    }
}
