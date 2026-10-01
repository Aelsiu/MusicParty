import axios from 'axios';
import { roomSession } from '../services/roomSession';

const client = axios.create({
    // 可以在这里配置 baseURL 或 timeout
    timeout: 10000
});

client.interceptors.request.use(config => {
    if (roomSession.roomId) config.headers['X-Room-ID'] = roomSession.roomId;
    if (roomSession.roomToken) config.headers['X-Room-Token'] = roomSession.roomToken;
    if (!config.skipManagementAuth && roomSession.managerToken && (roomSession.ownerAccess || config.url.startsWith('/api/rooms') || config.url.startsWith('/api/admin'))) {
        config.headers.Authorization = `Bearer ${roomSession.managerToken}`;
    }
    return config;
});

// 响应拦截器：可以在这里统一处理 401/403 等错误
client.interceptors.response.use(
    res => res.data,
    error => Promise.reject(error)
);

export default client;
