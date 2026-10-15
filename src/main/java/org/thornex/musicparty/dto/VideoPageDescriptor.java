package org.thornex.musicparty.dto;

/** Page identifiers, titles and durations come exclusively from the metadata API. */
public record VideoPageDescriptor(String canonicalId, long cid, int page, String title,
                                  long durationMs, boolean withinDurationLimit) {}
