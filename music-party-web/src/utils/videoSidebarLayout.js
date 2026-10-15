/** Preserve a usable video width before allocating either 320px desktop sidebar. */
export function sidebarLayout(mode, width, height) {
    if (mode !== 'VIDEO') return {members:width>=768,queue:width>=768};
    const videoWidth=Math.max(640,Math.min(960,Math.max(0,height-220)*1.15));
    const budget=width-videoWidth;
    return {members:budget>=640,queue:budget>=320};
}
