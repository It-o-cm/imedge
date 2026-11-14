package com.intermarche.edge.service.deploy;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/** Unit tests for {@link AppVersion}. */
class AppVersionTest {

    /** BO-08-03-07: numbers are compared segment by segment, a missing one counting as zero. */
    @Test
    void numbersAreComparedSegmentBySegment() {
        assertTrue(AppVersion.compare("1.4.2", "1.10") < 0);
        assertTrue(AppVersion.compare("2.0", "1.9.9") > 0);
        assertEquals(0, AppVersion.compare("1.4", "1.4.0"));
        assertEquals(0, AppVersion.compare("1.4.0", "1.4"));
    }

    /** BO-08-03-07: at equal numbers a qualified version ranks below the plain one. */
    @Test
    void aQualifiedVersionRanksBelowThePlainOne() {
        assertTrue(AppVersion.isLower("1.0-SNAPSHOT", "1.0"));
        assertFalse(AppVersion.isLower("1.0", "1.0-SNAPSHOT"));
        assertFalse(AppVersion.isLower("1.0", "1.0"));
        assertTrue(AppVersion.isLower(null, "0.1"));
    }

    /** BO-08-03-07: a segment that is not a number reads as zero, a null version as zero. */
    @Test
    void numbersReadBadSegmentsAsZero() {
        assertArrayEquals(new int[]{1, 0, 3}, AppVersion.numbers(" 1.x.3-RC1 "));
        assertArrayEquals(new int[]{0}, AppVersion.numbers(null));
        assertFalse(AppVersion.qualified(null));
        assertFalse(AppVersion.qualified("1.0"));
        assertTrue(AppVersion.qualified("1.0-RC"));
    }

    /** BO-08-03-01: a package is fingerprinted by the SHA-256 of its UTF-8 bytes. */
    @Test
    void sha256FingerprintsTheContent() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppVersion.sha256("abc"));
    }

    /** An unavailable digest is a state error, not a silent empty fingerprint. */
    @Test
    void sha256FailsLoudlyWithoutTheAlgorithm() {
        try (MockedStatic<MessageDigest> digests = mockStatic(MessageDigest.class)) {
            digests.when(() -> MessageDigest.getInstance("SHA-256")).thenThrow(new NoSuchAlgorithmException("none"));
            assertThrows(IllegalStateException.class, () -> AppVersion.sha256("abc"));
        }
    }
}
