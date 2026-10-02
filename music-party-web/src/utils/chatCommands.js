export const chatCommands = [
    { name: 'clear', defaultParameter: 'self', parameters: [
        { name: 'self', label: '清空自己点的待播歌曲' },
        { name: 'all', label: '清空所有待播歌曲', manager: true },
        { name: 'dead', label: '清空离线成员歌曲', manager: true },
        { name: 'chat', label: '清空聊天记录', manager: true }
    ] },
    { name: 'stream', defaultParameter: 'now', parameters: [
        { name: 'now', label: '获取个人直播流链接' },
        { name: 'on', label: '开启直播推流', manager: true },
        { name: 'off', label: '关闭直播推流', manager: true }
    ] },
    { name: 'jump', defaultParameter: 'off', manager: true, parameters: [
        { name: 'on', label: '允许所有人跳转进度' },
        { name: 'off', label: '禁止跳转进度' },
        { name: 'dog', label: '仅管理与点歌者可跳转' }
    ] },
    { name: 'admin', label: '打开管理台 / 许可验证' },
    { name: 'code', defaultParameter: 'copy', manager: true, parameters: [
        { name: 'copy', label: '复制最新配对码' },
        { name: 'on', label: '在播放页展示配对码' },
        { name: 'off', label: '取消播放页配对码展示' }
    ] },
    { name: 'rooms', label: '打开房间管理', manager: true }
];

export function commandChoices(manager) {
    return chatCommands.flatMap(command => {
        if (command.manager && !manager) return [];
        if (!command.parameters) return [{ text: `//${command.name}`, label: command.label }];
        return command.parameters.filter(parameter => manager || !parameter.manager)
            .map(parameter => ({ text: `//${command.name} ${parameter.name}`, label: parameter.label }));
    });
}

export function completeCommand(text, manager) {
    const trimmed = text.trimStart();
    if (!trimmed) return null;
    const match = /^(?:\/\/)?([a-z]+)(?:\s+([a-z]*))?$/i.exec(trimmed);
    if (!match) return null;
    const name = match[1].toLowerCase();
    const commands = chatCommands.filter(command => command.name.startsWith(name));
    if (match[2] === undefined) return commands.length === 1 && (!commands[0].manager || manager) ? `//${commands[0].name}` : null;
    const command = commands.find(command => command.name === name);
    if (!command?.parameters || (command.manager && !manager)) return null;
    const parameters = command.parameters.filter(parameter => (manager || !parameter.manager)
        && parameter.name.startsWith(match[2].toLowerCase()));
    return parameters.length === 1 ? `//${command.name} ${parameters[0].name}` : null;
}

export function parseChatCommand(text) {
    const match = /^\/\/([a-z]+)(?:\s+(.*))?$/i.exec(text.trim());
    if (!match) return null;
    const command = chatCommands.find(command => command.name === match[1].toLowerCase());
    if (!command) return null;
    const parameter = (match[2] || command.defaultParameter || '').trim().toLowerCase();
    return { name: command.name, parameter, valid: command.parameters
        ? command.parameters.some(option => option.name === parameter) : parameter === '' };
}
