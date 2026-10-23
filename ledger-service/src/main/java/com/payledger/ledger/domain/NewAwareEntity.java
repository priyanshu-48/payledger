package com.payledger.ledger.domain;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Ids are assigned by us, so Spring Data cannot tell new from existing by a null id and would
 * issue a SELECT before every insert. Reporting isNew explicitly keeps inserts to a single statement.
 */
@MappedSuperclass
abstract class NewAwareEntity<ID> implements Persistable<ID> {

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }
}
