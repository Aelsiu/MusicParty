// Validate the target before leaving, and let the old audio engine unmount before entering it.
export async function enterManagedRoom(room, { currentRoomId, isActive, manage, leave, settle, enter, canEnter = () => true }) {
    const sourceId = currentRoomId();
    if (!isActive()) return 'cancelled';
    if (room.id === sourceId) return 'current';
    const target = await manage(room.id);
    if (!isActive() || currentRoomId() !== sourceId) return 'cancelled';
    leave();
    await settle();
    if (!canEnter()) return 'cancelled';
    enter(target);
    return 'entered';
}
