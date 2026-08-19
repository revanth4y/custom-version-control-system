package com.gitforge.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable pagination envelope.
 *
 * <p>Serialising Spring's {@code Page} directly is discouraged because its JSON
 * shape is not part of the public contract; this record pins the fields the
 * client depends on.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
