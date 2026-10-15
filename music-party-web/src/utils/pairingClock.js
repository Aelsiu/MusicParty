export function pairingRemaining(nextUpdateAt, now = Date.now(), offset = 0) {
    if (!Number.isFinite(nextUpdateAt) || nextUpdateAt <= 0) return 0;
    return Math.max(0, Math.ceil((nextUpdateAt - now - offset) / 1000));
}

export function pairingProgress(nextUpdateAt, intervalMinutes, now = Date.now(), offset = 0) {
    if (!Number.isInteger(intervalMinutes) || intervalMinutes < 1 || intervalMinutes > 60) return 0;
    return Math.min(100, pairingRemaining(nextUpdateAt, now, offset) / (intervalMinutes * 60) * 100);
}

export function pairingCountdown(seconds) {
    return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`;
}
