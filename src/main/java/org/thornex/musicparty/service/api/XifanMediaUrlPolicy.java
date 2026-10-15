package org.thornex.musicparty.service.api;

import java.net.*;
import java.util.*;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** XIFAN's observed media origins, independent of Bilibili CDN grants. */
public final class XifanMediaUrlPolicy {
    public static URI validate(String value) {
        try {
            URI uri=URI.create(value);
            boolean origin="play.xfvod.pro".equals(uri.getHost())&&uri.getPort()==8088
                || "apn.moedot.net".equals(uri.getHost())&&(uri.getPort()==-1||uri.getPort()==443)
                || "bjdownload.pan.wo.cn".equals(uri.getHost())&&uri.getPort()==30443;
            if(!"https".equals(uri.getScheme()) || !origin
                    || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null) throw failure("MEDIA_ROUTE_UNSUPPORTED");
            return uri;
        } catch(IllegalArgumentException error){throw failure("MEDIA_ROUTE_UNSUPPORTED");}
    }
    static URI redirect(URI from,String location){
        try{
            validate(from.toString());
            URI next=validate(from.resolve(location).toString());
            if(!"bjdownload.pan.wo.cn".equals(next.getHost())
                || !("apn.moedot.net".equals(from.getHost()) || "bjdownload.pan.wo.cn".equals(from.getHost())))throw failure("MEDIA_ROUTE_UNSUPPORTED");
            return next;
        }catch(IllegalArgumentException invalid){throw failure("MEDIA_ROUTE_UNSUPPORTED");}
    }
    public static boolean publicAddress(InetAddress ip) {
        if(ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
        byte[] b=ip.getAddress();int a=b[0]&255;
        if(b.length==16) return (a&0xe0)==0x20; // global unicast only
        int c=b[1]&255;
        return a!=0 && a!=10 && a!=127 && a<224 && !(a==100 && c>=64 && c<=127)
            && !(a==169 && c==254) && !(a==172 && c>=16 && c<=31) && !(a==192 && (c==168 || c==0 || c==2))
            && !(a==198 && (c==18 || c==19 || c==51)) && !(a==203 && c==0);
    }
    public static List<InetAddress> resolve(URI uri) {
        validate(uri.toString());if("play.xfvod.pro".equals(uri.getHost()))return Dns.RESOLVER.resolve(uri);
        try{var ips=List.of(InetAddress.getAllByName(uri.getHost()));if(ips.isEmpty()||ips.size()>8||ips.stream().anyMatch(ip->!publicAddress(ip)))throw failure("MEDIA_ROUTE_UNSUPPORTED");return ips;}
        catch(UnknownHostException unavailable){throw failure("XIFAN_UNAVAILABLE");}
    }
    private static final class Dns { private static final XifanMediaDnsResolver RESOLVER=new XifanMediaDnsResolver(); }
}
