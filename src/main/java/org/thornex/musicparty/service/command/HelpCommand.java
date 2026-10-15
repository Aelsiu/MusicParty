package org.thornex.musicparty.service.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.User;

@Component
@org.thornex.musicparty.room.RoomScoped
@RequiredArgsConstructor
public class HelpCommand implements ChatCommand {
    private final CommandSupport commands;
    @Override public String getCommand() { return "help"; }
    @Override public void execute(String args, User user) {
        if (!args.isBlank()) { commands.reply(user, "用法：//help，不需要参数"); return; }
        commands.event(user, "HELP_TRIGGER", "OPEN_HELP", null);
    }
}
