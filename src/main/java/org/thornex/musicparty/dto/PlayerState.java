package org.thornex.musicparty.dto;

import java.util.List;

public record PlayerState(
        NowPlayingInfo nowPlaying,
        List<MusicQueueItem> queue,
        String playMode,
        boolean isShuffle,
        boolean isFairShuffle,
        boolean allowOfflineShuffle,
        List<UserSummary> onlineUsers,
        boolean isPaused,
        boolean isPauseLocked,
        boolean isSkipLocked,
        boolean isPlayModeLocked,
        boolean isLoading,
        int streamListenerCount,
        boolean isStreamEnabled,
        boolean isVoteSkipEnabled,
        double voteSkipThreshold,
        int voteSkipWaitTime,
        int currentVotes,
        int eligibleUsers,
        AppConfigSummary config,
        int protocolVersion, String playbackEpoch, String mediaMode,
        long modeEpoch, long stateRevision, long queueRevision, long serverTime,
        String sourceStatus, Long sourceRetryAt,
        String playIntent, double playbackRate, VideoWaitState videoWait,
        VideoFeatures videoFeatures, String sourceErrorCode, boolean policyRecoveryRequired,
        VideoPending videoPending, long videoHistoryRevision
) {
    public PlayerState(NowPlayingInfo nowPlaying, List<MusicQueueItem> queue, String playMode,
            boolean isShuffle, boolean isFairShuffle, boolean allowOfflineShuffle,
            List<UserSummary> onlineUsers, boolean isPaused, boolean isPauseLocked,
            boolean isSkipLocked, boolean isPlayModeLocked, boolean isLoading,
            int streamListenerCount, boolean isStreamEnabled, boolean isVoteSkipEnabled,
            double voteSkipThreshold, int voteSkipWaitTime, int currentVotes, int eligibleUsers,
            AppConfigSummary config) {
        this(nowPlaying, queue, playMode, isShuffle, isFairShuffle, allowOfflineShuffle,
            onlineUsers, isPaused, isPauseLocked, isSkipLocked, isPlayModeLocked, isLoading,
            streamListenerCount, isStreamEnabled, isVoteSkipEnabled, voteSkipThreshold,
            voteSkipWaitTime, currentVotes, eligibleUsers, config,
            3, null, "MUSIC", 0, 0, 0, System.currentTimeMillis(), "EMPTY", null,
            isPaused ? "PAUSED" : "PLAYING", 1, null, VideoFeatures.NONE, null, false, null, 0);
    }
    public PlayerState versioned(String epoch, String mode, long modeVersion, long stateVersion,
            long queueVersion, long time, String sourceState, Long retryAt) {
        return new PlayerState(nowPlaying, List.copyOf(queue), playMode, isShuffle,
            isFairShuffle, allowOfflineShuffle, List.copyOf(onlineUsers), isPaused,
            isPauseLocked, isSkipLocked, isPlayModeLocked, isLoading, streamListenerCount,
            isStreamEnabled, isVoteSkipEnabled, voteSkipThreshold, voteSkipWaitTime,
            currentVotes, eligibleUsers, config, 3, epoch, mode, modeVersion, stateVersion,
            queueVersion, time, sourceState, retryAt, playIntent, playbackRate, videoWait, videoFeatures, sourceErrorCode, policyRecoveryRequired, videoPending, videoHistoryRevision);
    }
    public PlayerState withVideoState(String intent, double rate, VideoWaitState wait) {
        return new PlayerState(nowPlaying, queue, playMode, isShuffle, isFairShuffle,
                allowOfflineShuffle, onlineUsers, isPaused, isPauseLocked, isSkipLocked,
                isPlayModeLocked, isLoading, streamListenerCount, isStreamEnabled,
                isVoteSkipEnabled, voteSkipThreshold, voteSkipWaitTime, currentVotes,
                eligibleUsers, config, protocolVersion, playbackEpoch, mediaMode, modeEpoch,
                stateRevision, queueRevision, serverTime, sourceStatus, sourceRetryAt, intent, rate, wait, videoFeatures, sourceErrorCode, policyRecoveryRequired, videoPending, videoHistoryRevision);
    }
    public record VideoFeatures(boolean history, boolean favoriteLinkImport, boolean interactionState, boolean resolutionPolicy) {
        public static final VideoFeatures NONE = new VideoFeatures(false,false,false,false);
        public static final VideoFeatures ALL = new VideoFeatures(true,true,true,true);
    }
    public record VideoPending(String queueId, String canonicalId, String origin) {}
    public PlayerState withP2(String error, boolean recovery, VideoPending pending, long historyRevision) {
        return new PlayerState(nowPlaying, queue, playMode, isShuffle, isFairShuffle, allowOfflineShuffle,
            onlineUsers,isPaused,isPauseLocked,isSkipLocked,isPlayModeLocked,isLoading,streamListenerCount,
            isStreamEnabled,isVoteSkipEnabled,voteSkipThreshold,voteSkipWaitTime,currentVotes,eligibleUsers,
            config,protocolVersion,playbackEpoch,mediaMode,modeEpoch,stateRevision,queueRevision,serverTime,
            sourceStatus,sourceRetryAt,playIntent,playbackRate,videoWait,VideoFeatures.ALL,error,recovery,pending,historyRevision);
    }
    public record AppConfigSummary(
            int maxQueueSize,
            int maxHistorySize,
            int maxUserSongs,
            int maxPlaylistImportSize,
            int maxChatHistorySize,
            long minChatIntervalMs,
            int maxChatMessageLength,
            String neteaseQuality,
            boolean neteaseEnabled,
            boolean bilibiliEnabled,
            int bilibiliMaxDurationMinutes,
            boolean voteSkipEnabled,
            double voteSkipThreshold,
            int voteSkipWaitTime,
            boolean neteaseCookieConfigured,
            PrivateDjConfigSummary privateDj,
            String seekPolicy,
            int videoMaxDurationMinutes,
            boolean bilibiliCookieConfigured, String videoMaxResolution, long videoPolicyRevision
    ) {
        public AppConfigSummary(int maxQueueSize,int maxHistorySize,int maxUserSongs,int maxPlaylistImportSize,
            int maxChatHistorySize,long minChatIntervalMs,int maxChatMessageLength,String neteaseQuality,
            boolean neteaseEnabled,boolean bilibiliEnabled,int bilibiliMaxDurationMinutes,boolean voteSkipEnabled,
            double voteSkipThreshold,int voteSkipWaitTime,boolean neteaseCookieConfigured,PrivateDjConfigSummary privateDj,
            String seekPolicy,int videoMaxDurationMinutes,boolean bilibiliCookieConfigured) {
            this(maxQueueSize,maxHistorySize,maxUserSongs,maxPlaylistImportSize,maxChatHistorySize,minChatIntervalMs,
                maxChatMessageLength,neteaseQuality,neteaseEnabled,bilibiliEnabled,bilibiliMaxDurationMinutes,
                voteSkipEnabled,voteSkipThreshold,voteSkipWaitTime,neteaseCookieConfigured,privateDj,seekPolicy,
                videoMaxDurationMinutes,bilibiliCookieConfigured,"UNLIMITED",0);
        }
        public AppConfigSummary(int maxQueueSize, int maxHistorySize, int maxUserSongs,
                int maxPlaylistImportSize, int maxChatHistorySize, long minChatIntervalMs,
                int maxChatMessageLength, String neteaseQuality, boolean neteaseEnabled,
                boolean bilibiliEnabled, int bilibiliMaxDurationMinutes, boolean voteSkipEnabled,
                double voteSkipThreshold, int voteSkipWaitTime, boolean neteaseCookieConfigured,
                PrivateDjConfigSummary privateDj, String seekPolicy) {
            this(maxQueueSize, maxHistorySize, maxUserSongs, maxPlaylistImportSize,
                maxChatHistorySize, minChatIntervalMs, maxChatMessageLength, neteaseQuality,
                neteaseEnabled, bilibiliEnabled, bilibiliMaxDurationMinutes, voteSkipEnabled,
                voteSkipThreshold, voteSkipWaitTime, neteaseCookieConfigured, privateDj,
                seekPolicy, 120, false, "UNLIMITED", 0);
        }
        public record PrivateDjConfigSummary(
                String mode,
                boolean fillBlankEnabled,
                boolean joinQueueEnabled,
                boolean custodyEnabled
        ) {}
    }
}
