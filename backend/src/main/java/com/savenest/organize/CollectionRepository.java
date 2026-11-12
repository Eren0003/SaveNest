package com.savenest.organize;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollectionRepository extends JpaRepository<UserCollection, UUID> {

    Optional<UserCollection> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<UserCollection> findByOwnerIdAndNormalizedName(UUID ownerId, String normalizedName);

    List<UserCollection> findByOwnerIdOrderBySortOrderAscNormalizedNameAsc(UUID ownerId);

    List<UserCollection> findByOwnerIdAndIdIn(UUID ownerId, Collection<UUID> ids);

    /** Rows: [collection id, count] for active, non-trashed bookmarks. */
    @Query("""
            select c.id, count(b) from Bookmark b join b.collections c
            where b.ownerId = :owner and b.deletedAt is null and b.lifecycleState = 'active'
            group by c.id
            """)
    List<Object[]> countActive(@Param("owner") UUID owner);
}
