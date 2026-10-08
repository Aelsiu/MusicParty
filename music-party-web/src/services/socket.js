import { roomSession } from './roomSession';
import { Client } from '@stomp/stompjs';

class SocketService {
    constructor() {
        this.client = null;
        this.connected = false;
        this.stompConfig = null;
        this.generation = 0;
        this.clientGeneration = 0;
        this.upgradeRequired = false;
    }

    /**
     * 初始化连接
     * @param {Object} authHeaders - { 'user-name':..., 'user-token':..., 'room-password':... }
     * @param {Object} callbacks - 回调函数集合
     * @param {Function} callbacks.onConnect - 连接成功
     * @param {Function} callbacks.onDisconnect - 连接断开
     * @param {Function} callbacks.onStompError - STOMP 错误 (如密码错误)
     * @param {Object} subscriptions - 订阅配置 { topic: callbackFn }
     */
    connect(authHeaders, callbacks, subscriptions) {
        // 避免重复连接
        if (this.upgradeRequired || (this.client && this.client.active)) return;

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const brokerURL = `${protocol}//${window.location.host}/ws`;

        const generation = ++this.generation;
        const roomId = roomSession.roomId;
        const current = () => generation === this.generation && roomId === roomSession.roomId;
        const client = new Client({
            brokerURL,
            connectHeaders: { ...authHeaders, 'playback-protocol': '3' },
            heartbeatIncoming: 10000,
            heartbeatOutgoing: 10000,
            reconnectDelay: 2000,
            beforeConnect: () => {
                if (!current()) { client.deactivate(); return; }
                client.connectHeaders = { ...authHeaders, 'playback-protocol': '3', 'room-id': roomId, 'room-token': roomSession.roomToken, 'management-token': roomSession.ownerAccess ? roomSession.managerToken : '' };
            },

            onConnect: (frame) => {
                if (!current()) { client.deactivate(); return; }
                this.connected = true;
                const connectionGeneration = ++this.clientGeneration;
                const connectionCurrent = () => current() && connectionGeneration === this.clientGeneration;

                // 1. 注册所有订阅
                Object.entries(subscriptions).forEach(([topic, handler]) => {
                    client.subscribe(topic.startsWith('/topic/') ? `/topic/rooms/${roomId}/${topic.slice(7)}` : topic, (message) => {
                        if (!connectionCurrent()) return;
                        try { handler(JSON.parse(message.body), connectionGeneration); } catch (error) { console.warn('Invalid socket message', error.name); }
                    });
                });

                // 2. 触发连接成功回调
                if (callbacks.onConnect) callbacks.onConnect(frame, connectionCurrent, connectionGeneration);
            },

            // 监听非正常关闭 (如网络中断、服务器重启)
            onWebSocketClose: () => {
                if (!current()) return;
                console.warn('WebSocket connection closed.');
                this.connected = false;
                this.clientGeneration++;
                // 触发断开回调，让 Store 感知状态变化
                if (callbacks.onDisconnect) callbacks.onDisconnect();
            },

            onDisconnect: () => {
                if (!current()) return;
                this.connected = false;
                this.clientGeneration++;
                if (callbacks.onDisconnect) callbacks.onDisconnect();
            },

            onStompError: (frame) => {
                if (!current()) return;
                if (`${frame.headers?.message || ''} ${frame.body || ''}`.includes('CLIENT_UPGRADE_REQUIRED')) {
                    this.upgradeRequired = true; client.reconnectDelay = 0; client.deactivate();
                }
                if (callbacks.onStompError) callbacks.onStompError(frame);
            }
        });

        this.client = client;
        client.activate();
    }

    /**
     * 发送指令 (通用)
     * @param {string} destination - 目标地址 (来自 WS_DEST)
     * @param {Object} body - 消息体
     */
    send(destination, body = {}) {
        if (this.client && this.connected) {
            this.client.publish({ destination, body: JSON.stringify(body) });
            return true;
        } else {
            console.warn('Socket not connected, cannot send:', destination);
            return false;
        }
    }

    /**
     * 强制重连 (用于网络恢复或从后台切回时)
     */
    forceReconnect() {
        if (!this.upgradeRequired && this.client && !this.client.active) {
            console.log('Force reconnecting socket...');
            this.client.activate();
        }
    }

    /**
     * 断开连接
     */
    disconnect() {
        this.generation++;
        this.clientGeneration++;
        if (this.client) {
            this.client.deactivate();
            this.client = null;
            this.connected = false;
        }
    }
}

// 导出单例
export const socketService = new SocketService();
