import { parseChatCommand } from '../utils/chatCommands.js';
import { isPairingCodeCommand } from './pairingCode.js';
import { isShareCommand } from './shareInvite.js';

// Called directly in the click/Enter handler so clipboard access retains the
// user gesture. Recognized local command families never enter room chat.
export function submitChatMessage(text, { chat, send, manager }) {
    const command = parseChatCommand(text);
    if (/^\/\/help(?:\s|$)/i.test(text.trim())) {
        if (command?.valid) window.dispatchEvent(new CustomEvent('musicparty:show-help'));
        else chat.privateNotice('用法：//help，不需要参数');
        return;
    }
    if (command && ['public', 'private'].includes(command.name) && !command.valid) {
        chat.privateNotice(`用法：//${command.name}，不需要参数`);
        return;
    }
    if (command && ['public', 'private'].includes(command.name) && manager === false) {
        chat.privateNotice('此指令仅限本房间 Owner 或 Root 使用'); return;
    }
    if (isPairingCodeCommand(text)) {
        return chat.runPairingCodeCommand(command?.parameter, command?.valid === true);
    }
    if (isShareCommand(text)) return chat.runShareCommand(command?.parameter, command?.valid === true);
    return send(text);
}
