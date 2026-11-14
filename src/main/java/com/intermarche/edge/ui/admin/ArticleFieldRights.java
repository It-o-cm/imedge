package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.ArticleFieldRight;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.EmployeeProfile;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which data of the article fiche the signed-in account may change
 * (BO-02-03-33), from the rights administered per profile.
 *
 * <p>The ARTICLE screen's own grant decides whether the account saves the
 * fiche at all; this decides, datum by datum, what that save may touch. A
 * datum no right names is open, a named datum is reserved to the profiles
 * named, and the administrator changes everything.
 */
@ApplicationScoped
public class ArticleFieldRights {

    /**
     * The data of the fiche an account may change.
     *
     * @param login the signed-in login, or null
     * @return the changeable data, empty for an unknown account
     */
    public Set<ArticleField> editable(String login) {
        Employee employee = login == null || login.isBlank() ? null : Employee.findActiveLogin(login);
        if (employee == null) {
            return EnumSet.noneOf(ArticleField.class);
        }
        if (employee.role == Employee.EmployeeRole.ADMIN) {
            return EnumSet.allOf(ArticleField.class);
        }
        Set<Long> profiles = new HashSet<>();
        for (EmployeeProfile binding : EmployeeProfile.forEmployee(employee.id)) {
            profiles.add(binding.profileId);
        }
        return editable(ArticleFieldRight.listAllOrdered(), profiles);
    }

    /**
     * Applies the rule to a set of rights and the profiles an account holds —
     * the pure part of the decision.
     *
     * @param rights the administered rights
     * @param profiles the ids of the profiles the account holds
     * @return the changeable data
     */
    static Set<ArticleField> editable(List<ArticleFieldRight> rights, Set<Long> profiles) {
        Set<String> restricted = new HashSet<>();
        Set<String> granted = new HashSet<>();
        for (ArticleFieldRight right : rights) {
            restricted.add(right.fieldCode);
            if (profiles.contains(right.profileId)) {
                granted.add(right.fieldCode);
            }
        }
        Set<ArticleField> editable = EnumSet.noneOf(ArticleField.class);
        for (ArticleField field : ArticleField.values()) {
            if (!restricted.contains(field.name()) || granted.contains(field.name())) {
                editable.add(field);
            }
        }
        return editable;
    }
}
