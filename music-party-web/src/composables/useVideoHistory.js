import { usePlaybackHistory } from './usePlaybackHistory';
export function useVideoHistory(player, open) { return usePlaybackHistory(player, open, 'VIDEO'); }
