// Persist ownership only after the server upgrades this same live room connection.
export async function verifyRoomOwner(key, { connection, login, promote, isCurrent, commit }) {
    if (!connection.roomId || !connection.sessionId || !isCurrent()) {
        throw new Error('房间连接已改变，请连接后重新验证');
    }

    const session = await login(key);
    if (!isCurrent()) throw new Error('房间连接已改变，请重新验证');
    const result = await promote(connection, session.token);
    if (!isCurrent()) throw new Error('房间连接已改变，请重新验证');
    if (result?.role !== 'OWNER' && result?.role !== 'ROOT') {
        throw new Error('房间权限升级失败，请重新验证');
    }

    commit(session);
    return session;
}
