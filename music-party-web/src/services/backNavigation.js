const handlers = new Map();
let keyboardDocument=null;
const keyboardBack=event=>{
    if(event.key==='Escape'&&!event.defaultPrevented&&!event.isComposing&&handleModalBack()){
        event.preventDefault();event.stopPropagation();
    }
};

export function registerBackHandler(priority, handle) {
    const id = Symbol();
    handlers.set(id, { priority, handle });
    const document=globalThis.document;
    if(!keyboardDocument&&document?.addEventListener){keyboardDocument=document;document.addEventListener('keydown',keyboardBack);}
    return () => {handlers.delete(id);if(!handlers.size&&keyboardDocument){keyboardDocument.removeEventListener?.('keydown',keyboardBack);keyboardDocument=null;}};
}

export function handleModalBack() {
    for (const { handle } of [...handlers.values()].sort((a, b) => b.priority - a.priority)) {
        if (handle()) return true;
    }
    return false;
}
