package com.savenest.bookmark;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookmarkRepository extends JpaRepository<Bookmark, UUID> {

    /** Ownership is part of every lookup so other users' rows are indistinguishable from missing ones. */
    Optional<Bookmark> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<Bookmark> findByOwnerIdAndDedupeKeyAndDeletedAtIsNull(UUID ownerId, String dedupeKey);

    /** Row lock so worker results and concurrent user PATCHes serialize (user edits are never lost). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bookmark b where b.id = :id and b.ownerId = :owner")
    Optional<Bookmark> lockByIdAndOwnerId(@Param("id") UUID id, @Param("owner") UUID owner);

    /** All of an owner's bookmarks (any lifecycle state, including trash) for export. */
    List<Bookmark> findByOwnerIdOrderByCreatedAtAscIdAsc(UUID ownerId);

    /** Trashed bookmark ids, for emptying the trash. */
    @Query("select b.id from Bookmark b where b.ownerId = :owner and b.deletedAt is not null")
    List<UUID> findTrashedIds(@Param("owner") UUID owner);

    /** Rows: [effective group, platform, count] for active, non-trashed bookmarks. */
    @Query("""
            select coalesce(b.groupOverride, b.detectedGroup), b.platform, count(b)
            from Bookmark b
            where b.ownerId = :owner and b.deletedAt is null and b.lifecycleState = 'active'
            group by coalesce(b.groupOverride, b.detectedGroup), b.platform
            """)
    List<Object[]> countByGroupAndPlatform(@Param("owner") UUID owner);
}
