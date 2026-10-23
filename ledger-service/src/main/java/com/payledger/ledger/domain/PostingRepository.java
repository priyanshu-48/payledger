package com.payledger.ledger.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<Posting, UUID> {
    List<Posting> findByEntryId(UUID entryId);
}
