package com.nest.jsonstore.profile.dto;

import java.time.Instant;

/** Headline numbers for the whole store: how many profiles, how many bytes of inputs. */
public record ProfileStats(long profiles, long inputBytes, Instant lastUpdatedAt) {
}
