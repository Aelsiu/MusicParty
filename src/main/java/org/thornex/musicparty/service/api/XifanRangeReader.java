package org.thornex.musicparty.service.api;

import io.netty.resolver.*;
import io.netty.util.concurrent.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Pinned DNS and normal HTTPS verification; disk redirects are checked individually. */
final class XifanRangeReader implements Mp4MetadataProbe.Reader, AutoCloseable {
    private static final Pattern RANGE=Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    private final URI uri;
    private final HttpClient client;
    private final HttpClient freshClient;
    private boolean freshConnections;
    private static final class RangeIgnored extends RuntimeException {}
    private static final class Redirect extends RuntimeException { final String location;Redirect(String location){this.location=location;} }
    private static final class Budget {
        int requests,bytes;long deadline;
        Duration attempt(int length){
            long now=System.nanoTime();if(deadline==0)deadline=now+15_000_000_000L;
            if(Thread.currentThread().isInterrupted() || now>=deadline || ++requests>16 || length<1 || length>4*1024*1024-bytes)throw failure("MEDIA_METADATA_UNAVAILABLE");
            bytes+=length;return Duration.ofNanos(Math.min(5_000_000_000L,deadline-now));
        }
    }
    private final Budget budget;
    private final int depth;
    private final Set<URI> visited;
    private final BiFunction<URI,String,URI> redirects;
    private final Function<URI,List<InetAddress>> addressLookup;
    private XifanRangeReader redirected;
    private final AddressResolverGroup<InetSocketAddress> resolver;
    private final ConnectionProvider connections;
    XifanRangeReader(String url) {
        this(XifanMediaUrlPolicy.validate(url));
    }
    private XifanRangeReader(URI uri){this(uri,XifanMediaUrlPolicy.resolve(uri));}
    // Local transport-test seam; production uses the policy-checked String constructor.
    XifanRangeReader(URI uri,List<InetAddress> ips) {
        this(uri,ips,XifanMediaUrlPolicy::redirect,XifanMediaUrlPolicy::resolve);
    }
    XifanRangeReader(URI uri,List<InetAddress> ips,BiFunction<URI,String,URI> redirects,Function<URI,List<InetAddress>> addressLookup){
        this(uri,ips,redirects,addressLookup,new Budget(),0,new HashSet<>(Set.of(uri)));
    }
    private XifanRangeReader(URI uri,List<InetAddress> ips,BiFunction<URI,String,URI> redirects,Function<URI,List<InetAddress>> addressLookup,Budget budget,int depth,Set<URI> visited){
        this.uri=uri;
        this.redirects=redirects;this.addressLookup=addressLookup;this.budget=budget;this.depth=depth;this.visited=visited;
        resolver=new AddressResolverGroup<>() {
            protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                return new AbstractAddressResolver<>(executor,InetSocketAddress.class) {
                    protected boolean doIsResolved(InetSocketAddress address){return !address.isUnresolved() && ips.contains(address.getAddress());}
                    protected void doResolve(InetSocketAddress address,Promise<InetSocketAddress> promise){promise.setSuccess(new InetSocketAddress(ips.getFirst(),address.getPort()));}
                    protected void doResolveAll(InetSocketAddress address,Promise<List<InetSocketAddress>> promise){promise.setSuccess(ips.stream().map(ip->new InetSocketAddress(ip,address.getPort())).toList());}
                };
            }
        };
        connections=ConnectionProvider.create("xifan-metadata",1);
        client=HttpClient.create(connections).resolver(resolver).followRedirect(false).disableRetry(true).responseTimeout(Duration.ofSeconds(5))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,5000).keepAlive(true);
        freshClient=HttpClient.newConnection().resolver(resolver).followRedirect(false).disableRetry(true).responseTimeout(Duration.ofSeconds(5))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,5000).keepAlive(false);
    }
    public Mp4MetadataProbe.Range read(long offset,int length) {
        if(redirected!=null)return redirected.read(offset,length);
        try{return readOnce(offset,length);}
        catch(org.thornex.musicparty.dto.VideoFavorites.Failure error){
            if(!(error.getCause() instanceof Redirect redirect))throw error;
            if(offset!=0 || depth>=3)throw failure("MEDIA_ROUTE_UNSUPPORTED");
            URI next=redirects.apply(uri,redirect.location);if(!visited.add(next))throw failure("MEDIA_ROUTE_UNSUPPORTED");
            redirected=new XifanRangeReader(next,addressLookup.apply(next),redirects,addressLookup,budget,depth+1,visited);
            return redirected.read(offset,length);
        }
    }
    URI mediaUri(){return redirected==null?uri:redirected.mediaUri();}
    private Mp4MetadataProbe.Range readOnce(long offset,int length){
        Duration timeout=budget.attempt(length);
        var range=(freshConnections?freshClient:client).headers(headers->{headers.set("Range","bytes="+offset+"-"+(offset+length-1));headers.set("Accept-Encoding","identity");})
            .get().uri(uri.toString()).response((response,body)->{
                if(response.status().code()!=206){
                    var rejected=failure(response.status().code()/100==3?"MEDIA_ROUTE_UNSUPPORTED":"MEDIA_METADATA_UNAVAILABLE");
                    // Some CDN nodes ignore Range when keep-alive is requested, even on the first read.
                    // Cancel at headers; the probe may budget one fresh-connection retry.
                    if(response.status().code()==200&&!freshConnections)rejected.initCause(new RangeIgnored());
                    if(Set.of(301,302,303,307,308).contains(response.status().code())){
                        String location=response.responseHeaders().get("Location");if(location!=null)rejected.initCause(new Redirect(location));
                    }
                    return reactor.core.publisher.Mono.error(rejected);
                }
                String contentRange=response.responseHeaders().get("Content-Range");var matcher=RANGE.matcher(contentRange==null?"":contentRange);
                if(!matcher.matches() || Long.parseLong(matcher.group(1))!=offset || Long.parseLong(matcher.group(2))!=offset+length-1
                        || response.responseHeaders().contains("Content-Encoding") && !"identity".equals(response.responseHeaders().get("Content-Encoding"))) return reactor.core.publisher.Mono.error(failure("MEDIA_METADATA_UNAVAILABLE"));
                long total=Long.parseLong(matcher.group(3));String etag=response.responseHeaders().get("ETag");String validator=etag!=null && !etag.startsWith("W/")?etag:null;
                return body.asByteArray().scan(new byte[0],(acc,next)->{if(acc.length+next.length>length)throw failure("MEDIA_METADATA_UNAVAILABLE");byte[] result=Arrays.copyOf(acc,acc.length+next.length);System.arraycopy(next,0,result,acc.length,next.length);return result;})
                    .last().map(bytes->new Mp4MetadataProbe.Range(bytes,total,validator));
            }).single().timeout(timeout)
            .onErrorMap(error->{
                if(error instanceof org.thornex.musicparty.dto.VideoFavorites.Failure)return error;
                var unavailable=failure("MEDIA_METADATA_UNAVAILABLE");
                if(error instanceof java.util.concurrent.TimeoutException || error instanceof io.netty.handler.timeout.ReadTimeoutException
                        || error instanceof io.netty.channel.ConnectTimeoutException)
                    unavailable.initCause(new java.util.concurrent.TimeoutException("MP4 range timed out"));
                return unavailable;
            }).block();
        return range;
    }
    public boolean prepareRetry(Throwable error){
        if(redirected!=null)return redirected.prepareRetry(error);
        if(error.getCause() instanceof RangeIgnored&&!freshConnections){freshConnections=true;return true;}
        return Mp4MetadataProbe.Reader.super.prepareRetry(error);
    }
    public void close(){if(redirected!=null)redirected.close();connections.dispose();resolver.close();}
}
