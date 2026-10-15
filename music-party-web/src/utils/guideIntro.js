export const GUIDE_INTRO_KEY = 'mp_help_intro_done_v1';
export function createGuideIntro(storage) {
    let completed = false;
    return {
        hasSeen() {
            if (completed) return true;
            try { return Boolean(storage?.getItem(GUIDE_INTRO_KEY) || storage?.getItem('mp_tutorial_done_v1')); }
            catch { return false; }
        },
        complete() {
            completed = true;
            try { storage?.setItem(GUIDE_INTRO_KEY, 'true'); } catch { /* This page still remembers the dismissal. */ }
        }
    };
}
