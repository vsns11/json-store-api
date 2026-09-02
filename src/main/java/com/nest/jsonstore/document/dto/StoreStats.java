package com.nest.jsonstore.document.dto;

import java.time.Instant;

/** Headline numbers for the whole store. */
public record StoreStats(long documents, long totalBytes, Instant lastUpdatedAt) {
}
