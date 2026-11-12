package com.savenest.organize;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    Optional<Tag> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<Tag> findByOwnerIdAndNormalizedName(UUID ownerId, String normalizedName);

    List<Tag> findByOwnerIdOrderByNormalizedNameAsc(UUID ownerId);

    List<Tag> findByOwnerIdAndNormalizedNameIn(UUID ownerId, Collection<String> normalizedNames);

    /** Race-safe get-or-create: concurrent inserts of the same name collapse into one row. */
    @Modifying
    @Query(value = """
            insert into tags (id, owner_id, name, normalized_name, created_at, updated_at)
            values (:id, :owner, :name, :normalized, now(), now())
            on conflict (owner_id, normalized_name) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("owner") UUID owner,
                       @Param("name") String name, @Param("normalized") String normalized);

    /** Rows: [tag id, count] for active, non-trashed bookmarks. */
    @Query("""
            select t.id, count(b) from Bookmark b join b.tags t
            where b.ownerId = :owner and b.deletedAt is null and b.lifecycleState = 'active'
            group by t.id
            """)
    List<Object[]> countActive(@Param("owner") UUID owner);

    /** Moves all links from {@code source} to {@code target}, skipping bookmarks that already have target. */
    @Modifying
    @Query(value = """
            insert into bookmark_tags (bookmark_id, tag_id)
            select bookmark_id, :target from bookmark_tags where tag_id = :source
            on conflict do nothing
            """, nativeQuery = true)
    int copyLinks(@Param("source") UUID source, @Param("target") UUID target);
}
