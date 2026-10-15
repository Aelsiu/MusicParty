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
        VideoPending videoPending, long videoHistoryRevision,
        QueueFeatures queueFeatures, String queuePendingId, long musicHistoryRevision,
        VideoAutoAdvance videoAutoAdvance
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
            isPaused ? "PAUSED" : "PLAYING", 1, null, VideoFeatures.NONE, null, false, null, 0, QueueFeatures.NONE, null, 0, null);
    }
    public PlayerState versioned(String epoch, String mode, long modeVersion, long stateVersion,
            long queueVersion, long time, String sourceState, Long retryAt) {
        return new PlayerState(nowPlaying, List.copyOf(queue), playMode, isShuffle,
            isFairShuffle, allowOfflineShuffle, List.copyOf(onlineUsers), isPaused,
            isPauseLocked, isSkipLocked, isPlayModeLocked, isLoading, streamListenerCount,
            isStreamEnabled, isVoteSkipEnabled, voteSkipThreshold, voteSkipWaitTime,
            currentVotes, eligibleUsers, config, 3, epoch, mode, modeVersion, stateVersion,
            queueVersion, time, sourceState, retryAt, playIntent, playbackRate, videoWait, videoFeatures, sourceErrorCode, policyRecoveryRequired, videoPending, videoHistoryRevision, queueFeatures, queuePendingId, musicHistoryRevision, videoAutoAdvance);
    }
    public PlayerState withVideoState(String intent, double rate, VideoWaitState wait) {
        return new PlayerState(nowPlaying, queue, playMode, isShuffle, isFairShuffle,
                allowOfflineShuffle, onlineUsers, isPaused, isPauseLocked, isSkipLocked,
                isPlayModeLocked, isLoading, streamListenerCount, isStreamEnabled,
                isVoteSkipEnabled, voteSkipThreshold, voteSkipWaitTime, currentVotes,
                eligibleUsers, config, protocolVersion, playbackEpoch, mediaMode, modeEpoch,
                stateRevision, queueRevision, serverTime, sourceStatus, sourceRetryAt, intent, rate, wait, videoFeatures, sourceErrorCode, policyRecoveryRequired, videoPending, videoHistoryRevision, queueFeatures, queuePendingId, musicHistoryRevision, videoAutoAdvance);
    }
    public record VideoFeatures(boolean history, boolean favoriteLinkImport, boolean interactionState, boolean resolutionPolicy, boolean inputResolve, boolean pageBatchImport, boolean pgcInput, boolean pgcEpisodeImport, boolean xifanSearch,boolean xifanEpisodeImport,boolean xifanMp4) {
        public VideoFeatures(boolean history,boolean favoriteLinkImport,boolean interactionState,boolean resolutionPolicy,boolean inputResolve,boolean pageBatchImport,boolean pgcInput,boolean pgcEpisodeImport){this(history,favoriteLinkImport,interactionState,resolutionPolicy,inputResolve,pageBatchImport,pgcInput,pgcEpisodeImport,false,false,false);}
        public VideoFeatures(boolean history, boolean favoriteLinkImport, boolean interactionState, boolean resolutionPolicy, boolean inputResolve, boolean pageBatchImport) { this(history,favoriteLinkImport,interactionState,resolutionPolicy,inputResolve,pageBatchImport,false,false); }
        public VideoFeatures(boolean history, boolean favoriteLinkImport, boolean interactionState, boolean resolutionPolicy) { this(history,favoriteLinkImport,interactionState,resolutionPolicy,false,false); }
        public static final VideoFeatures NONE = new VideoFeatures(false,false,false,false);
        public static final VideoFeatures ALL = new VideoFeatures(true,true,true,true,true,true,true,true,true,true,true);
    }
    public record QueueFeatures(boolean cancelTop, boolean musicHistory) {
        public static final QueueFeatures NONE = new QueueFeatures(false, false);
        public static final QueueFeatures ALL = new QueueFeatures(true, true);
    }
    public PlayerState withQueueState(QueueFeatures features, String pendingId, long historyRevision) {
        return new PlayerState(nowPlaying, queue, playMode, isShuffle, isFairShuffle, allowOfflineShuffle,
            onlineUsers,isPaused,isPauseLocked,isSkipLocked,isPlayModeLocked,isLoading,streamListenerCount,
            isStreamEnabled,isVoteSkipEnabled,voteSkipThreshold,voteSkipWaitTime,currentVotes,eligibleUsers,
            config,protocolVersion,playbackEpoch,mediaMode,modeEpoch,stateRevision,queueRevision,serverTime,
            sourceStatus,sourceRetryAt,playIntent,playbackRate,videoWait,videoFeatures,sourceErrorCode,
            policyRecoveryRequired,videoPending,videoHistoryRevision,features,pendingId,historyRevision,videoAutoAdvance);
    }
    public record VideoPending(String queueId, String canonicalId, String origin) {}
    public PlayerState withP2(String error, boolean recovery, VideoPending pending, long historyRevision) {
        return new PlayerState(nowPlaying, queue, playMode, isShuffle, isFairShuffle, allowOfflineShuffle,
            onlineUsers,isPaused,isPauseLocked,isSkipLocked,isPlayModeLocked,isLoading,streamListenerCount,
            isStreamEnabled,isVoteSkipEnabled,voteSkipThreshold,voteSkipWaitTime,currentVotes,eligibleUsers,
            config,protocolVersion,playbackEpoch,mediaMode,modeEpoch,stateRevision,queueRevision,serverTime,
            sourceStatus,sourceRetryAt,playIntent,playbackRate,videoWait,VideoFeatures.ALL,error,recovery,pending,historyRevision,queueFeatures,queuePendingId,musicHistoryRevision,videoAutoAdvance);
    }
    /** Server-owned countdown; manual pause changes intent without extending its deadline. */
    public record VideoAutoAdvance(long deadlineAt, long durationMs) {}
    public PlayerState withVideoAutoAdvance(VideoAutoAdvance countdown) {
        return new PlayerState(nowPlaying, queue, playMode, isShuffle, isFairShuffle, allowOfflineShuffle,
            onlineUsers,isPaused,isPauseLocked,isSkipLocked,isPlayModeLocked,isLoading,streamListenerCount,
            isStreamEnabled,isVoteSkipEnabled,voteSkipThreshold,voteSkipWaitTime,currentVotes,eligibleUsers,
            config,protocolVersion,playbackEpoch,mediaMode,modeEpoch,stateRevision,queueRevision,serverTime,
            sourceStatus,sourceRetryAt,playIntent,playbackRate,videoWait,videoFeatures,sourceErrorCode,
            policyRecoveryRequired,videoPending,videoHistoryRevision,queueFeatures,queuePendingId,musicHistoryRevision,countdown);
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
            boolean bilibiliCookieConfigured, String videoMaxResolution, long videoPolicyRevision, boolean xifanEnabled
    ) {
        public AppConfigSummary(int maxQueueSize,
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
            boolean bilibiliCookieConfigured, String videoMaxResolution, long videoPolicyRevision){this(maxQueueSize,maxHistorySize,maxUserSongs,maxPlaylistImportSize,maxChatHistorySize,minChatIntervalMs,maxChatMessageLength,neteaseQuality,neteaseEnabled,bilibiliEnabled,bilibiliMaxDurationMinutes,voteSkipEnabled,voteSkipThreshold,voteSkipWaitTime,neteaseCookieConfigured,privateDj,seekPolicy,videoMaxDurationMinutes,bilibiliCookieConfigured,videoMaxResolution,videoPolicyRevision,true); }
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
