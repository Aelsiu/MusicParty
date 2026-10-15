import dayjs from 'dayjs';

export const formatDuration = (ms) => {
    const seconds = Number.isFinite(ms) && ms > 0 ? Math.floor(ms / 1000) : 0;
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor(seconds / 60) % 60;
    return [hours, minutes, seconds % 60].map(value => String(value).padStart(2, '0')).join(':');
};

export const formatTimeNow = () => {
    return dayjs().format('HH:mm:ss');
};
