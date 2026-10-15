package org.thornex.musicparty.service.command;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.User;
@Component
@org.thornex.musicparty.room.RoomScoped
@RequiredArgsConstructor
public class PrivateCommand implements ChatCommand {
    private final PublicCommand command;
    @Override public String getCommand() { return "private"; }
    @Override public void execute(String args, User user) { command.change(args, user, false); }
}
