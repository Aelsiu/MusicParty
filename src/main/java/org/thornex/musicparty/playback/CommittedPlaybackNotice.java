package org.thornex.musicparty.playback;

/** Published only after an engine has returned and released its own monitor. */
public record CommittedPlaybackNotice(Object event, String playbackEpoch, String mediaMode, long modeEpoch) {}
