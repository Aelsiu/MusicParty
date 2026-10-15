import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { registerBackHandler, handleModalBack } from '../services/backNavigation.js';
import { roomSession } from '../services/roomSession.js';
import { createVideoPresentationContext, videoPresentationKey } from './videoPresentationContext.js';

let orientationOwner = null;

// Presentation changes DOM destinations/platform windows, never the media owner
// or source. The same video keeps useVideo's native/local playback handlers.
export function useVideoPresentation(surfaceRef, videoRef, options = {}) {
    const context = options.context || inject(videoPresentationKey, null) || createVideoPresentationContext();
    const doc = options.document || globalThis.document, orientation = (options.screen || globalThis.screen)?.orientation;
    const ownerToken = Symbol('video presentation owner'), pageMode = context.pageMode;
    const nativeVideoFullscreen = ref(false), pipActive = ref(false), requestPending = ref(false), presentationError = ref('');
    const returnMode = ref('INLINE');
    const pipSupported = computed(() => !options.mobile && !!doc?.pictureInPictureEnabled && typeof videoRef.value?.requestPictureInPicture === 'function');
    const scopeGetter = options.scopeKey || (() => [roomSession.generation, options.player?.mediaMode]);
    let scopeSnapshot = scopeGetter();
    let alive = true, generation = 0, pending = null, unregisterSurface, registeredSurface, registeredVideo, opener;
    let claimed = false, fullscreenRequestGeneration = null, pipRequestGeneration = null, nativeRequestGeneration = null;
    let afterContainerExit = null, orientationClaim = null, orientationAcquired = false;
    const current = () => {
        const scope = scopeGetter(), sameScope = Array.isArray(scope) && Array.isArray(scopeSnapshot)
            ? scope.length === scopeSnapshot.length && scope.every((value, index) => Object.is(value, scopeSnapshot[index])) : Object.is(scope, scopeSnapshot);
        return alive && sameScope && context.isCurrent(ownerToken) && (!options.player || options.player.mediaMode === 'VIDEO');
    };
    const reveal = () => { if (current()) { context.setControlsVisible(ownerToken, true); options.onRevealControls?.(); } };
    const setMode = mode => { if (context.setPageMode(ownerToken, mode)) { options.onModeChange?.(mode); reveal(); return true; } return false; };
    const saveFocus = trigger => { opener = trigger?.currentTarget || trigger || doc?.activeElement; };
    const restoreFocus = () => {
        if (!current()) return;
        const inSurface = registeredSurface?.contains?.(opener);
        const eligible = opener?.isConnected !== false && !opener?.closest?.('[inert]') && (pageMode.value === 'INLINE' || inSurface);
        (eligible && opener?.focus ? opener : registeredSurface)?.focus?.({ preventScroll: true });
    };
    const releaseOrientation = () => {
        if (orientationAcquired && orientationOwner === orientationClaim) { orientation?.unlock?.(); orientationOwner = null; }
        orientationAcquired = false;
    };
    const lockOrientation = () => {
        if (!orientation?.lock) return;
        const claim = { ownerToken, generation }; orientationClaim = claim; orientationOwner = claim;
        try {
            Promise.resolve(orientation.lock('landscape')).then(() => {
                if (orientationOwner !== claim) return;
                if (current() && claim.generation === generation && pageMode.value === 'CONTAINER_FULLSCREEN') orientationAcquired = true;
                else { orientation.unlock?.(); orientationOwner = null; }
            }, () => { if (orientationOwner === claim) orientationOwner = null; });
        } catch { if (orientationOwner === claim) orientationOwner = null; }
    };
    const finish = operation => { if (pending === operation) { pending = null; requestPending.value = false; } };
    const begin = (kind, element, trigger) => {
        if (!current() || requestPending.value) return null;
        saveFocus(trigger); presentationError.value = '';
        const operation = { kind, element, generation }; pending = operation; requestPending.value = true; return operation;
    };
    const valid = operation => current() && operation.generation === generation
        && operation.element === (operation.kind.includes('PiP') || operation.kind.includes('Native') ? videoRef.value : surfaceRef.value);
    const mayCleanOldElement = element => context.isCurrent(ownerToken) || !context.registration.value
        || context.registration.value.surface !== element && !context.registration.value.surface.contains?.(element);
    const cleanupLate = operation => {
        // A replaced owner may reuse the same DOM element; old completions cannot
        // exit a newer owner's platform presentation.
        if (current() && (pending || pageMode.value === 'CONTAINER_FULLSCREEN' || pipActive.value)) return;
        if (!mayCleanOldElement(operation.element)) return;
        if (doc?.fullscreenElement === operation.element) doc.exitFullscreen?.().catch?.(() => {});
        if (doc?.pictureInPictureElement === operation.element) doc.exitPictureInPicture?.().catch?.(() => {});
    };
    const onFullscreenChange = () => {
        if (!current()) return;
        if (doc.fullscreenElement === registeredSurface) {
            if (fullscreenRequestGeneration !== generation) { if (!requestPending.value) doc.exitFullscreen?.().catch?.(() => {}); return; }
            if (pageMode.value !== 'CONTAINER_FULLSCREEN') { setMode('CONTAINER_FULLSCREEN'); lockOrientation(); }
            if (pending?.kind === 'enterFullscreen') finish(pending);
        } else if (pageMode.value === 'CONTAINER_FULLSCREEN') {
            setMode(afterContainerExit || returnMode.value); afterContainerExit = null; releaseOrientation();
            if (pending?.kind === 'exitFullscreen') finish(pending);
            restoreFocus();
        }
    };
    const onEnterPiP = () => {
        if (doc.pictureInPictureElement !== registeredVideo) return;
        if (!current() || pipRequestGeneration !== generation) { if (mayCleanOldElement(registeredVideo)) doc.exitPictureInPicture?.().catch?.(() => {}); return; }
        pipActive.value = true; if (pending?.kind === 'enterPiP') finish(pending); reveal();
    };
    const onLeavePiP = () => { if (current() && doc.pictureInPictureElement !== registeredVideo) { pipActive.value = false; if (pending?.kind === 'exitPiP') finish(pending); reveal(); restoreFocus(); } };
    const onNativeFullscreenStart = () => {
        if (!current() || nativeRequestGeneration !== generation) { if (mayCleanOldElement(registeredVideo)) { try { registeredVideo?.webkitExitFullscreen?.(); } catch {} } return; }
        nativeVideoFullscreen.value = true; if (pending?.kind === 'enterNative') finish(pending); reveal();
    };
    const onNativeFullscreenEnd = () => { if (current()) { nativeVideoFullscreen.value = false; if (pending?.kind === 'exitNative') finish(pending); reveal(); restoreFocus(); } };
    const exitPiP = async trigger => {
        if (doc?.pictureInPictureElement !== videoRef.value || !doc.exitPictureInPicture) return false;
        const operation = begin('exitPiP', videoRef.value, trigger); if (!operation) return false;
        try { await doc.exitPictureInPicture(); return valid(operation) && !pipActive.value; }
        catch { if (valid(operation)) presentationError.value = '无法退出画中画，请使用原生窗口关闭'; finish(operation); return false; }
        finally { if (!valid(operation)) finish(operation); }
    };
    const exitFullscreen = async (trigger, nextMode = null) => {
        if (!current()) return false;
        if (nativeVideoFullscreen.value) {
            const operation = begin('exitNative', videoRef.value, trigger); if (!operation) return false;
            try { if (!videoRef.value.webkitExitFullscreen) throw new Error(); videoRef.value.webkitExitFullscreen(); return true; }
            catch { if (valid(operation)) presentationError.value = '请使用原生全屏控件退出'; finish(operation); return false; }
            finally { if (!valid(operation)) finish(operation); }
        }
        if (doc?.fullscreenElement !== registeredSurface || !doc.exitFullscreen) return false;
        const operation = begin('exitFullscreen', registeredSurface, trigger); if (!operation) return false;
        afterContainerExit = nextMode;
        try { await doc.exitFullscreen(); return valid(operation) && pageMode.value !== 'CONTAINER_FULLSCREEN'; }
        catch { if (valid(operation)) { afterContainerExit = null; presentationError.value = '无法退出全屏，请再次操作'; } finish(operation); return false; }
        finally { if (!valid(operation)) finish(operation); }
    };
    const requestFullscreen = async trigger => {
        if (!current() || requestPending.value) return false;
        if (nativeVideoFullscreen.value || doc?.fullscreenElement === registeredSurface) return exitFullscreen(trigger);
        if (doc?.pictureInPictureElement === videoRef.value || pipActive.value) {
            const requestGeneration = generation;
            await exitPiP(trigger);
            if (current() && requestGeneration === generation) presentationError.value = '已请求退出画中画，请再次点击进入全屏';
            return false;
        }
        if (doc?.fullscreenElement || doc?.pictureInPictureElement) { presentationError.value = '请先退出当前全屏或画中画'; return false; }
        returnMode.value = pageMode.value === 'WEB_FULLSCREEN' ? 'WEB_FULLSCREEN' : 'INLINE';
        const native = typeof registeredSurface.requestFullscreen !== 'function' || doc.fullscreenEnabled === false;
        const element = native ? videoRef.value : registeredSurface;
        const operation = begin(native ? 'enterNative' : 'enterFullscreen', element, trigger); if (!operation) return false;
        if (native) nativeRequestGeneration = generation; else fullscreenRequestGeneration = generation;
        try {
            if (native) {
                if (!element?.webkitEnterFullscreen) throw new Error();
                element.webkitEnterFullscreen(); presentationError.value = '原生全屏只显示视频和本端控件';
            } else {
                // Invoke in the original click gesture; confirmation comes only
                // from fullscreenchange, never from the request Promise.
                await element.requestFullscreen();
            }
            if (!valid(operation)) { cleanupLate(operation); return false; }
            return native ? nativeVideoFullscreen.value : pageMode.value === 'CONTAINER_FULLSCREEN';
        } catch {
            if (valid(operation)) {
                if (native && !nativeVideoFullscreen.value) nativeRequestGeneration = null;
                else if (!native && pageMode.value !== 'CONTAINER_FULLSCREEN') fullscreenRequestGeneration = null;
                presentationError.value = '当前平台无法进入视频全屏'; reveal(); restoreFocus();
            }
            finish(operation);
            return false;
        } finally { if (!valid(operation)) finish(operation); }
    };
    const toggleWebFullscreen = async trigger => {
        if (!current() || requestPending.value) return false;
        if (doc?.pictureInPictureElement === videoRef.value || pipActive.value) {
            const requestGeneration = generation, exited = await exitPiP(trigger);
            if (!exited || !current() || requestGeneration !== generation) return false;
            saveFocus(trigger); return setMode('WEB_FULLSCREEN');
        }
        if (nativeVideoFullscreen.value) { const requestGeneration = generation; await exitFullscreen(trigger);
            if (current() && requestGeneration === generation) presentationError.value = '请退出原生全屏后再次点击网页全屏'; return false; }
        if (pageMode.value === 'CONTAINER_FULLSCREEN') return exitFullscreen(trigger, 'WEB_FULLSCREEN');
        saveFocus(trigger); const next = pageMode.value === 'WEB_FULLSCREEN' ? 'INLINE' : 'WEB_FULLSCREEN';
        if (!setMode(next)) return false;
        if (next === 'INLINE') restoreFocus();
        return true;
    };
    const togglePiP = async trigger => {
        if (!current() || requestPending.value) return false;
        if (!pipSupported.value || videoRef.value.readyState < 1) { presentationError.value = options.mobile ? '手机不开放主动画中画' : '当前浏览器或视频暂不支持画中画'; return false; }
        if (doc.pictureInPictureElement === videoRef.value || pipActive.value) return exitPiP(trigger);
        if (nativeVideoFullscreen.value || doc.fullscreenElement === registeredSurface) {
            const requestGeneration = generation;
            await exitFullscreen(trigger); if (current() && requestGeneration === generation) presentationError.value = '已请求退出全屏，请再次点击进入画中画'; return false;
        }
        if (doc.fullscreenElement || doc.pictureInPictureElement) { presentationError.value = '请先退出当前全屏或画中画'; return false; }
        if (pageMode.value === 'WEB_FULLSCREEN') setMode('INLINE');
        const operation = begin('enterPiP', videoRef.value, trigger); if (!operation) return false;
        pipRequestGeneration = generation;
        try { await operation.element.requestPictureInPicture(); if (!valid(operation)) { cleanupLate(operation); return false; }
            presentationError.value = '原生画中画只显示视频，不显示弹幕'; return pipActive.value; }
        catch { if (valid(operation)) { if (!pipActive.value) pipRequestGeneration = null; presentationError.value = '画中画暂时不可用，请再次操作'; reveal(); }
            finish(operation); return false; }
        finally { if (!valid(operation)) finish(operation); }
    };
    const reset = () => {
        scopeSnapshot = scopeGetter();
        generation++; pending = null; requestPending.value = false; afterContainerExit = null;
        fullscreenRequestGeneration = pipRequestGeneration = nativeRequestGeneration = null; releaseOrientation();
        if (context.isCurrent(ownerToken)) { options.closeMenu?.(); context.closeMenu(); context.resetPresentation(ownerToken); options.onModeChange?.('INLINE'); }
        if (doc?.fullscreenElement === registeredSurface && mayCleanOldElement(registeredSurface)) doc.exitFullscreen?.().catch?.(() => {});
        if (doc?.pictureInPictureElement === registeredVideo && mayCleanOldElement(registeredVideo)) doc.exitPictureInPicture?.().catch?.(() => {});
        if (nativeVideoFullscreen.value && mayCleanOldElement(registeredVideo)) { try { registeredVideo?.webkitExitFullscreen?.(); } catch {} }
        nativeVideoFullscreen.value = false; pipActive.value = false; presentationError.value = ''; returnMode.value = 'INLINE'; reveal();
    };
    const handleBack = () => {
        if (!current()) return false;
        if (options.closeMenu?.() || context.closeMenu()) { reveal(); return true; }
        if (requestPending.value) { reset(); return true; }
        if (nativeVideoFullscreen.value || pageMode.value === 'CONTAINER_FULLSCREEN') { exitFullscreen(); return true; }
        if (pipActive.value) { exitPiP(); return true; }
        if (pageMode.value === 'WEB_FULLSCREEN') { toggleWebFullscreen(); return true; }
        return false;
    };
    const keydown = event => { if (event.key === 'Escape' && !event.defaultPrevented && current() && handleModalBack()) { event.preventDefault(); event.stopPropagation(); } };
    const videoEvents = { enterpictureinpicture: onEnterPiP, leavepictureinpicture: onLeavePiP,
        webkitbeginfullscreen: onNativeFullscreenStart, webkitendfullscreen: onNativeFullscreenEnd };
    const bind = () => {
        if (claimed && !context.isCurrent(ownerToken)) return;
        const surface = surfaceRef.value, dock = options.expandedDock?.value, video = videoRef.value;
        if (surface !== registeredSurface || dock !== context.registration.value?.expandedDock) {
            if (registeredSurface) reset(); unregisterSurface?.(); unregisterSurface = null; registeredSurface = surface;
            if (surface && dock) { unregisterSurface = context.registerVideoSurface({ surface, expandedDock: dock, ownerToken }); claimed = true; reveal(); }
        }
        if (video !== registeredVideo) {
            for (const [event, handler] of Object.entries(videoEvents)) registeredVideo?.removeEventListener?.(event, handler);
            registeredVideo = video;
            for (const [event, handler] of Object.entries(videoEvents)) registeredVideo?.addEventListener?.(event, handler);
        }
    };
    watch([surfaceRef, videoRef, () => options.expandedDock?.value], bind, { flush: 'post' });
    watch(scopeGetter, reset, { flush: 'post' });
    const unregisterBack = registerBackHandler(120, handleBack);
    onMounted(() => { bind(); doc?.addEventListener?.('fullscreenchange', onFullscreenChange); doc?.addEventListener?.('keydown', keydown); });
    onBeforeUnmount(() => {
        reset(); alive = false; unregisterBack(); unregisterSurface?.();
        doc?.removeEventListener?.('fullscreenchange', onFullscreenChange); doc?.removeEventListener?.('keydown', keydown);
        for (const [event, handler] of Object.entries(videoEvents)) registeredVideo?.removeEventListener?.(event, handler);
    });
    return { ownerToken, pageMode, returnMode, nativeVideoFullscreen, pipActive, requestPending, presentationError, pipSupported,
        requestFullscreen, toggleWebFullscreen, togglePiP, reset, handleBack };
}
