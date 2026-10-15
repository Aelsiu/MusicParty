package org.thornex.musicparty.dto;

public record VideoSeasonDescriptor(long seasonId, String title, String cover, int totalEpisodes,
        int returnedEpisodes, boolean truncated) {}
