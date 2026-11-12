package com.savenest.bookmark;

import com.savenest.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookmarkServiceProgressTest {

    @Test
    void acceptsProgressMatchingContentType() {
        assertEquals("watched", BookmarkService.validateProgress("video", "watched"));
        assertEquals("wishlist", BookmarkService.validateProgress("product", "wishlist"));
        assertEquals("none", BookmarkService.validateProgress("channel", "none"));
    }

    @Test
    void rejectsMismatchedProgress() {
        assertThrows(ApiException.class, () -> BookmarkService.validateProgress("product", "watched"));
        assertThrows(ApiException.class, () -> BookmarkService.validateProgress("channel", "read"));
    }
}
