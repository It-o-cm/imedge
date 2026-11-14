package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A SAVED SELECTION of deployment targets, recalled to compose the next lot
 * (BO-08-03-02, "groupée par rappel d'une sélection précédente").
 *
 * <p>The lines are the same as an imported list: one point of sale per line,
 * or {@code PDV;REGISTER} for one register, or {@code CENTRAL}.
 */
@Entity
@Table(name = "deployment_selection")
public class DeploymentSelection extends PanacheEntity {

    /** The selection's name, unique. */
    @Column(name = "name", nullable = false, unique = true, length = 60)
    public String name;

    /** The target lines. */
    @Lob
    @Column(name = "lines", nullable = false)
    public String lines;

    /** Who saved it. */
    @Column(name = "created_by", length = 80)
    public String createdBy;

    /** When it was saved. */
    @Column(name = "created_at")
    public LocalDateTime createdAt;

    /**
     * Finds a selection by its name.
     *
     * @param name the name
     * @return the selection, or null
     */
    public static DeploymentSelection findByName(String name) {
        return find("name", name).firstResult();
    }

    /**
     * Every selection, by name.
     *
     * @return the selections
     */
    public static List<DeploymentSelection> listOrdered() {
        return list("order by name");
    }
}
