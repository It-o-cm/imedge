package com.intermarche.pos.domain.people;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link CrudOption}.
 * <p>
 * A pure label-carrying enum with no branches: instantiating every constant
 * and exercising the getter meets the coverage target.
 */
class CrudOptionTest {

    /**
     * Every constant exposes its French label.
     */
    @Test
    void labelsAreExposed() {
        assertEquals("Visualisation", CrudOption.VIEW.getLabel());
        assertEquals("Création", CrudOption.CREATE.getLabel());
        assertEquals("Modification", CrudOption.UPDATE.getLabel());
        assertEquals("Suppression", CrudOption.DELETE.getLabel());
    }

    /**
     * The declared constants are the four CRUD options.
     */
    @Test
    void declaresFourOptions() {
        assertEquals(4, CrudOption.values().length);
        assertNotNull(CrudOption.valueOf("DELETE"));
    }
}
