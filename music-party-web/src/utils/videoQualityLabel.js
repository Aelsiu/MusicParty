export function shortVideoQuality(label, resolution) {
  const value=String(label||'');const match=value.match(/(\d{3,4})\s*p\b/i);
  if(match)return `${match[1]}P`;
  if(Number.isInteger(resolution)&&resolution>0)return `${resolution}P`;
  if(!value)return '—';if(/加载/.test(value))return '加载中';
  return /MP4/i.test(value)?'MP4':/扩展/.test(value)?'扩展':'未知';
}
