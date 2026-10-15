const point = (id, title, summary, items) => ({ id, title, summary, items: items.filter(Boolean), selector: `[data-guide="${id}"]` });

// Descriptions read the live UI context; they never send room commands.
export function getMainGuidePoints({ player = {}, user = {}, room = {} } = {}) {
    const video = player.mediaMode === 'VIDEO';
    const guest = !!user.isGuest;
    const manager = !!room.ownerAccess && !guest;
    const config = player.config || {};
    const ready = player.synchronized ? '' : '房间正在同步，请稍等再操作。';
    const member = guest ? '请先设置昵称，再参与操作。' : '';
    const requirements = [member, ready].filter(Boolean).join(' ');
    const history = video ? player.videoFeatures?.history : player.queueFeatures?.musicHistory;
    const queueSearch = '点 SEARCH 搜索点播，手机先展开队列。';
    const queuePermission = guest
        ? '设置昵称后才能操作待播内容；成员只能删除自己点的，管理者可以删除任意待播内容。'
        : manager
            ? '你可以删除任意待播内容；这里的置顶规则与其他成员相同。'
            : '你可以置顶待播内容，也可以删除自己点的内容。';
    const queuePriority = player.playMode === 'SHUFFLE'
        ? player.isFairShuffle
            ? '公平随机时点一次置顶，在这位成员轮到时先播；再点一次，全房间优先。'
            : '全部随机时点一次置顶，优先播这条内容；再点一次，全房间优先。'
        : '当前模式点一次置顶，就会在全房间优先播放。';
    const seekPermission = config.seekPolicy === 'ALL'
        ? '当前允许所有已设置昵称的成员跳转进度。'
        : config.seekPolicy === 'OWNER_AND_ENQUEUER'
            ? '当前只有管理者和正在播放内容的点播者可以跳转。'
            : '当前房间禁止跳转，管理者也不能从这里跳转。';
    const points = [
        point('help', '功能引导', '有问题时，来这里查看功能说明。', [
            '电脑把鼠标停在气泡上阅读，手机点气泡阅读。',
            '点另一个气泡切换说明，点空白处收起卡片。',
            '按 ESC、App 返回键，或点击顶部提示退出引导。'
        ]),
        point('settings', '设置', '把界面调成喜欢的样子，也可以在这里绑定平台用户。', [
            '主题、音乐可视化、歌词预览和弹幕设置只影响本设备。',
            '绑定网易云或 B站用户后，可在搜索中查看用户歌单。',
            '这里绑定的是歌单用户，房间的平台登录请到管理终端扫码。'
        ]),
        point('members', '在线成员与昵称', '看看谁在线，也能在这里改昵称和聊天。', [
            '点击自己的名字修改昵称；列表还会标出当前点播者和点赞成员。',
            '手机先展开成员面板，底部可以聊天和输入指令。',
            '输入 //help 查看引导，输入 //admin 打开管理终端或验证管理身份。',
            guest ? '请先设置昵称，再参与点播和普通聊天。' : ''
        ]),
        point('chat', '聊天与快捷指令', '和房客聊聊天，也可以在这里使用快捷指令。', [
            '展开后切换 CHAT 和 SYSTEM，查看聊天或系统消息；向上滚动可看更早的记录。',
            '输入框旁的快捷指令按钮，只列出你当前可以使用的指令。',
            '输入 //help 查看引导，输入 //admin 打开管理终端或验证管理身份。',
            guest ? '普通聊天需要先设置昵称，查看 //help 不需要。' : ''
        ]),
        point('mode', '房间与媒体模式', '这里查看房间正在使用的音频或视频模式。', [
            manager ? '点击模式按钮，确认后即可切换。' : '你目前可以查看模式，切换需要管理权限。',
            '切换会保留两种模式的队列和进度，但都需要手动开始播放。',
            ready
        ]),
        point('return', '返回房间入口', '想换个房间，可以从这里返回入口。', [
            '点击一次开始退出确认。',
            '3 秒内再点一次就会退出当前房间，返回后可选择其他房间。'
        ]),
        point('queue', '播放队列', `这里查看当前${video ? '视频' : '音频'}模式的待播内容。`, [
            queueSearch,
            history ? '切到“历史”查看最近播过的内容，成员可点“重新点播”。' : '',
            queuePermission,
            queuePriority,
            `${player.queueFeatures?.cancelTop ? '点“取消置顶”恢复正常排队。' : ''}准备播放中的内容不能置顶${player.queueFeatures?.cancelTop ? '或取消置顶' : ''}。${video ? '' : ' FM条目不能操作。'}`
        ]),
        point('search', '搜索点播', video ? '找想看的视频，并加入房间队列。' : '找想听的音乐，并加入房间队列。', video ? [
            '选择当前可用的 B站或 XIFAN 来源，暂不可用的入口会变灰。',
            'B站可输入关键词、BV/AV号或视频链接，再按需选择分P。',
            '收藏夹、短链接和分享文字也可粘贴，能否识别以页面提示为准。',
            'XIFAN可搜索或粘贴作品、播放链接，再选剧集和线路。',
            requirements
        ] : [
            '选择房间已开放的网易云或 B站音源，输入关键词搜索。',
            '绑定平台用户后，还能从用户歌单中点播。',
            'LIKESONG 是本设备保存的点赞列表，也可重新点播。',
            requirements
        ]),
        point('source', '内容来源', '底栏小封面带你打开当前内容的来源页面。', [
            '点击小封面即可打开来源页面。',
            !video ? '想点赞当前音乐，请点击主画面的大封面。' : '',
            '没有正在播放的内容时，请先点播。'
        ]),
        point('progress', '播放进度', '这里查看进度，允许跳转时可让全房间一起跳到指定位置。', [
            seekPermission,
            '有权限时点击进度条，或用左右键移动 5 秒、Home/End 跳到首尾。',
            player.synchronized ? '内容还没准备好或跳转冷却未结束时，请稍等再试。' : '房间正在同步，请稍等；内容未就绪或冷却未结束时也暂时不能跳转。',
            guest ? '游客请先设置昵称，房间权限允许后才能跳转。' : ''
        ]),
        point('pause', '房间播放与暂停', '这个按钮控制房间里所有人的播放和暂停。', [
            player.isPauseLocked ? '暂停已锁定，成员和管理者都不能在这里暂停。' : '点击按钮暂停播放。',
            player.isPauseLocked ? '已暂停时仍可点击恢复播放。' : '再点一次恢复播放。',
            requirements
        ]),
        point('next', '下一项与投票', '想换一首歌或一个视频，就用这里。', [
            player.isVoteSkipEnabled ? `其他成员在播放满 ${player.voteSkipWaitTime ?? 15} 秒后可投票，再点可撤回；达到设定比例就切换。` : '当前未开启投票，点击即可让房间进入下一项。',
            player.isVoteSkipEnabled ? '点播者切自己的内容无需投票；这里的管理者也遵循同样规则。' : '',
            !player.isVoteSkipEnabled ? '切换只影响当前音频或视频模式。' : '',
            player.isSkipLocked ? '切歌已锁定，这个按钮暂时不能使用，点播者和管理者也一样。' : '',
            requirements
        ]),
        point('play-mode', '播放顺序', '这里选择整个房间当前模式的播放顺序。', [
            `点击依次切换顺序播放、随机播放和${video ? '单项' : '单曲'}循环。`,
            '公平随机和离线内容是否参与，可在管理终端设置。',
            player.isPlayModeLocked ? '播放顺序已锁定，目前不能在这里修改。' : '',
            requirements
        ]),
        point('volume', '本机音量', '这里调整本设备的音量，其他房客不会受影响。', [
            '拖动滑杆调整大小，点击百分比可直接输入 0%–100%。',
            '点喇叭图标静音，再点取消静音。',
            '手机看视频时，先点音量按钮展开；手机听音乐可到精简模式调整。'
        ])
    ];
    if (room.shareEnabled) points.splice(5, 0, point('share', '分享房间', '把房间邀请链接分享给想一起听或看的人。', [
        '点击 SHARE 复制邀请链接。',
        '能否分享由房间管理者设置，邀请链接不会授予管理权限。'
    ]));
    if (!video) points.push(
        point('like', '点赞与 LIKESONG', '喜欢正在播放的音乐，可以点个赞留个记号。', [
            '电脑点击大封面点赞；小屏界面先点一次，再点确认。',
            '这次播放中只能点赞一次，进度条会标记点赞位置。',
            '点赞会保存到本设备的 LIKESONG，可在搜索页重新点播。',
            requirements
        ]),
        point('lyrics', '同步歌词', '这里的歌词会跟着音乐一起变化。', [
            '当前一句会用主题色标出。',
            '在设置中可选择提前显示 0–2 行。'
        ]),
        point('download', '下载音频', '想保存正在播放的音乐，可以从这里下载。', [
            '点击下载按钮，保存当前播放的音频。',
            '保存方式因浏览器或 App 而异，失败时会尝试打开音频链接。'
        ]),
        point('lite', '精简模式', '想专心听歌，可以切到更简单的界面。', [
            '这里只保留歌曲信息和本机音量，手机听音乐也能在这里调音量。',
            '可选择后台播放时自动进入精简模式。',
            '点“退出精简模式”回到主界面，就能再次打开帮助。'
        ])
    );
    else points.push(
        point('video-surface', '视频画面与状态', '这里一起看视频，底栏负责全房间的播放进度。', [
            '画面保持视频原本的比例。',
            '画面内的提示会告诉你是否正在缓冲、视频来源有问题或需要恢复观看。',
            '出现“全员等待”时会停在当前进度，准备完成后一起继续播放。'
        ]),
        point('video-source', '播放器与刷新源', '视频来源遇到问题时，可从这里刷新。', [
            '刷新会为全房间重新获取当前视频，保留播放进度。',
            '还没点播视频时请先点播；解析中或刷新冷却未结束时，稍等再试。',
            '支持扩展播放时，可点旁边按钮切换播放器，只影响本设备。',
            requirements
        ]),
        point('video-danmaku', '视频弹幕', '在这里开启或关闭你这台设备的弹幕。', [
            '点击“弹”切换显示，不会改变其他房客的弹幕。',
            '在设置中调整区域、透明度、字号和速度。',
            '加载失败时可用“重试弹幕”再试，XIFAN没有弹幕入口。'
        ]),
        point('video-preferences', '倍速与画质', '在这里调整房间倍速和本机画质。', [
            manager ? '倍速会让整个房间一起跟随，你可在菜单中选择 0.5–2 倍。' : '倍速需要管理者修改，整个房间一起跟随；你可以查看当前倍速。',
            '画质只影响本设备，自动模式会选当前可用的最高档，按钮显示实际画质。',
            '可选画质取决于视频来源、设备和系统上限；没有视频或可选档位时，画质按钮不可用。',
            ready
        ]),
        point('video-view', '观看布局', '这些按钮只调整本设备的视频显示方式。', [
            '网页全屏把视频铺满页面，全屏按钮使用浏览器或 App 的全屏功能。',
            '电脑支持时，可以用画中画小窗继续看。',
            '网页或视频全屏时，先退出全屏，再从顶部帮助查看引导。'
        ])
    );
    return points;
}
