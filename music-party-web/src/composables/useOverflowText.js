import {ref,watch,onMounted,onBeforeUnmount,nextTick} from 'vue';
export function overflowMotion(contentWidth,containerWidth){
  const distance=Math.max(0,contentWidth-containerWidth);const travel=distance/45;
  return {distance:distance>1?distance:0,duration:(2+2*travel)*1000,hold:1/(2+2*travel)};
}
export function useOverflowText(container,content,text){
  const reduced=ref(false),overflow=ref(false);let resize,intersection,animation,visible=true,hover=false,focus=false,alive=true;
  let hoverQuery,motionQuery,ancestor;
  const stop=()=>{animation?.cancel();animation=null;};
  function update(){
    stop();const outer=container.value,inner=content.value;if(!outer||!inner)return;
    const motion=overflowMotion(inner.scrollWidth||0,outer.clientWidth||0);overflow.value=motion.distance>0;
    if(!overflow.value||reduced.value||!visible||globalThis.document?.hidden||hoverQuery?.matches&&!hover&&!focus||!inner.animate)return;
    animation=inner.animate([{transform:'translateX(0)',offset:0},{transform:'translateX(0)',offset:motion.hold},
      {transform:`translateX(-${motion.distance}px)`,offset:.5},{transform:`translateX(-${motion.distance}px)`,offset:.5+motion.hold},{transform:'translateX(0)',offset:1}],{duration:motion.duration,iterations:Infinity,easing:'linear'});
  }
  const enter=()=>{hover=true;update();},leave=()=>{hover=false;update();},focusIn=()=>{focus=true;update();},focusOut=event=>{if(!ancestor?.contains?.(event.relatedTarget)){focus=false;update();}};
  const preferences=()=>{reduced.value=motionQuery?.matches===true;update();};
  watch(text,async()=>{await nextTick();if(alive)update();});
  onMounted(()=>{
    ancestor=container.value?.closest?.('button,a,[tabindex]')||container.value;
    hoverQuery=globalThis.window?.matchMedia?.('(hover:hover) and (pointer:fine)');motionQuery=globalThis.window?.matchMedia?.('(prefers-reduced-motion:reduce)');
    hoverQuery?.addEventListener?.('change',update);motionQuery?.addEventListener?.('change',preferences);
    ancestor?.addEventListener?.('mouseenter',enter);ancestor?.addEventListener?.('mouseleave',leave);ancestor?.addEventListener?.('focusin',focusIn);ancestor?.addEventListener?.('focusout',focusOut);
    globalThis.document?.addEventListener?.('visibilitychange',update);
    if(typeof ResizeObserver!=='undefined'){resize=new ResizeObserver(update);resize.observe(container.value);resize.observe(content.value);}
    if(typeof IntersectionObserver!=='undefined'){intersection=new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;update();});intersection.observe(container.value);}
    globalThis.document?.fonts?.addEventListener?.('loadingdone',update);globalThis.document?.fonts?.ready?.then(()=>{if(alive)update();});preferences();
  });
  onBeforeUnmount(()=>{alive=false;stop();resize?.disconnect();intersection?.disconnect();ancestor?.removeEventListener?.('mouseenter',enter);ancestor?.removeEventListener?.('mouseleave',leave);ancestor?.removeEventListener?.('focusin',focusIn);ancestor?.removeEventListener?.('focusout',focusOut);globalThis.document?.removeEventListener?.('visibilitychange',update);globalThis.document?.fonts?.removeEventListener?.('loadingdone',update);hoverQuery?.removeEventListener?.('change',update);motionQuery?.removeEventListener?.('change',preferences);});
  return {reduced,overflow};
}
