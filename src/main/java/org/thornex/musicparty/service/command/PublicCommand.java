package org.thornex.musicparty.service.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.room.RoomContext;
import org.thornex.musicparty.room.RoomRepository;

@Component
@org.thornex.musicparty.room.RoomScoped
@RequiredArgsConstructor
public class PublicCommand implements ChatCommand {
    private final CommandSupport commands;
    private final RoomRepository rooms;
    @Override public String getCommand() { return "public"; }
    @Override public void execute(String args, User user) { change(args, user, true); }
    void change(String args, User user, boolean value) {
        String command = value ? "public" : "private";
        if (!args.isBlank()) { commands.reply(user, "用法：//" + command + "，不需要参数"); return; }
        if (!commands.requireManager(user)) return;
        try {
            String id = RoomContext.require();
            if (rooms.room(id).publicRoom() == value) { commands.reply(user, value ? "房间已处于公开状态" : "房间已处于私有状态"); return; }
            rooms.setPublicRoom(id, value);
            commands.reply(user, value ? "已设为公开房间" : "已设为私有房间");
        } catch (RuntimeException error) { commands.reply(user, "房间状态保存失败，请重试"); }
    }
}
