package org.thornex.musicparty.service.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.room.RoomContext;
import org.thornex.musicparty.room.RoomLifecycleService;
import org.thornex.musicparty.room.RoomRepository;
import org.thornex.musicparty.room.RoomScoped;

import java.util.Set;

@Component
@RoomScoped
@RequiredArgsConstructor
public class ShareCommand implements ChatCommand {
    private final CommandSupport commands;
    private final RoomRepository repository;
    private final RoomLifecycleService lifecycle;

    @Override public String getCommand() { return "share"; }

    @Override public void execute(String args, User user) {
        String parameter=CommandSupport.parameter(args,"");
        if (!Set.of("","on","off").contains(parameter)) {
            commands.reply(user,"用法：//share [on|off]，不带参数复制邀请链接");
            return;
        }
        String room=RoomContext.require();
        if (!parameter.isEmpty()) {
            if (!commands.requireManager(user)) return;
            boolean enabled="on".equals(parameter);
            repository.setShareEnabled(room,enabled);lifecycle.shareChanged(room);
            commands.reply(user,enabled?"已开启房间分享":"已关闭房间分享");
            return;
        }
        if (!repository.room(room).shareEnabled()) {
            commands.reply(user,"本房间已关闭分享，请联系管理员使用 //share on 开启");
            return;
        }
        // The sender fetches the latest code and reports success after clipboard completion.
        commands.event(user,PlayerAction.SHARE_TRIGGER.name(),"COPY_ROOM_INVITE",null);
    }
}
