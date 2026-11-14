package com.intermarche.edge.service;

import com.intermarche.edge.domain.store.NodeEchelon;
import com.intermarche.edge.domain.store.Store;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Tells where THIS node sits in the organisation (BO-01-01-03).
 *
 * <p>The point of sale is the commissioned one ({@code pos.pdv.number}); a
 * node commissioned before that key existed falls back on the code of its
 * store record, which is what the profile assignments were matched against.
 */
@ApplicationScoped
public class NodeEchelonService {

    /** This node's own point-of-sale number, when commissioned. */
    @ConfigProperty(name = "pos.pdv.number")
    Optional<String> pdvNumber;

    /**
     * Returns this node's point-of-sale number.
     *
     * @return the commissioned number, else the store code, else null
     */
    public String currentPdv() {
        String configured = pdvNumber == null ? null : pdvNumber.orElse(null);
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        Store store = Store.<Store>findAll().firstResult();
        return store == null ? null : store.code;
    }

    /**
     * Returns this node's place in the organisation.
     *
     * @return the point of sale, its enseigne and its country, as far as known
     */
    public NodeEchelon current() {
        return NodeEchelon.of(currentPdv());
    }
}
