package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.ArticleFieldRight;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.EmployeeProfile;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ArticleFieldRights} and {@link ArticleField}
 * (BO-02-03-33): no account, unknown account, administrator, and the
 * open / reserved-granted / reserved-denied arms of the rule.
 */
class ArticleFieldRightsTest {

    /**
     * Builds a right.
     *
     * @param code the datum
     * @param profileId the profile
     * @return the right
     */
    private ArticleFieldRight right(String code, long profileId) {
        ArticleFieldRight right = new ArticleFieldRight();
        right.fieldCode = code;
        right.profileId = profileId;
        return right;
    }

    /**
     * A null or blank login and an unknown account change nothing; the
     * administrator changes everything.
     */
    @Test
    void unknownAccountsChangeNothingAndTheAdministratorEverything() {
        ArticleFieldRights rights = new ArticleFieldRights();
        assertTrue(rights.editable(null).isEmpty());
        assertTrue(rights.editable(" ").isEmpty());
        Employee admin = new Employee();
        admin.role = Employee.EmployeeRole.ADMIN;
        try (MockedStatic<Employee> employees = mockStatic(Employee.class)) {
            employees.when(() -> Employee.findActiveLogin("ghost")).thenReturn(null);
            employees.when(() -> Employee.findActiveLogin("admin")).thenReturn(admin);
            assertTrue(rights.editable("ghost").isEmpty());
            assertEquals(EnumSet.allOf(ArticleField.class), rights.editable("admin"));
        }
    }

    /**
     * Another account reads its profiles: an open datum is changeable, a datum
     * reserved to one of its profiles too, a datum reserved to another profile
     * is not.
     */
    @Test
    void anAccountChangesTheOpenAndGrantedData() {
        ArticleFieldRights rights = new ArticleFieldRights();
        Employee chef = new Employee();
        chef.id = 3L;
        chef.role = Employee.EmployeeRole.CASHIER;
        EmployeeProfile binding = new EmployeeProfile();
        binding.profileId = 7L;
        try (MockedStatic<Employee> employees = mockStatic(Employee.class);
                MockedStatic<EmployeeProfile> profiles = mockStatic(EmployeeProfile.class);
                MockedStatic<ArticleFieldRight> stored = mockStatic(ArticleFieldRight.class)) {
            employees.when(() -> Employee.findActiveLogin("chef")).thenReturn(chef);
            profiles.when(() -> EmployeeProfile.forEmployee(3L)).thenReturn(List.of(binding));
            stored.when(ArticleFieldRight::listAllOrdered).thenReturn(List.of(right("VAT", 9L), right("EAN", 7L)));
            Set<ArticleField> editable = rights.editable("chef");
            assertTrue(editable.contains(ArticleField.NAME));
            assertTrue(editable.contains(ArticleField.EAN));
            assertFalse(editable.contains(ArticleField.VAT));
        }
    }

    /**
     * Every datum carries a French label and its constant name as code.
     */
    @Test
    void everyFieldHasALabelAndACode() {
        for (ArticleField field : ArticleField.values()) {
            assertFalse(field.getLabel().isBlank());
            assertEquals(field.name(), field.getCode());
        }
        assertEquals("Code TVA", ArticleField.VAT.getLabel());
    }
}
