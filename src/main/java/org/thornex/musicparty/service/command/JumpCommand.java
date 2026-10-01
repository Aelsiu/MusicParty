package org.thornex.musicparty.service.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.QueuePersistenceService;

@Component
@org.thornex.musicparty.room.RoomScoped
@RequiredArgsConstructor
public class JumpCommand implements ChatCommand {
    private final CommandSupport commands;
    private final AppProperties properties;
    private final MusicPlayerService player;
    private final QueuePersistenceService persistence;

    @Override public String getCommand() { return "jump"; }

    @Override public void execute(String args, User user) {
        String parameter = CommandSupport.parameter(args, "off");
        String policy = switch (parameter) {
            case "on" -> "ALL";
            case "off" -> "DISABLED";
            case "dog" -> "OWNER_AND_ENQUEUER";
            default -> null;
        };
        if (policy == null) {
            commands.reply(user, "用法：//jump [on|off|dog]，不带参数默认 off");
            return;
        }
        if (!commands.requireManager(user)) return;
        properties.getPlayer().setSeekPolicy(policy);
        player.broadcastFullPlayerState();
        persistence.saveNow();
        commands.reply(user, switch (parameter) {
            case "on" -> "已允许所有成员跳转播放进度";
            case "dog" -> "仅 Owner、Root 和点歌者可跳转播放进度";
            default -> "已禁止跳转播放进度";
        });
    }
}
