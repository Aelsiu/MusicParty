package org.thornex.musicparty.service.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Reads container metadata only. The transport must enforce Range, deadlines and public DNS. */
public final class Mp4MetadataProbe {
    public record Range(byte[] bytes, long totalLength, String validator) {}
    public interface Reader {
        Range read(long offset, int length);
        default boolean prepareRetry(Throwable error){return error.getCause() instanceof java.util.concurrent.TimeoutException;}
    }
    public record Metadata(long durationMs, int width, int height, long totalLength, String validator) {}
    private record Box(String type, int payload, int end) {}
    private int requests, bytes;
    private long total = -1;
    private String validator;
    private final long deadline;
    public Mp4MetadataProbe() { deadline = System.nanoTime() + 15_000_000_000L; }
    private byte[] read(Reader reader, long offset, int length) {
      for(int attempt=0;;attempt++) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline || ++requests > 16
                || length < 1 || length > 4*1024*1024-bytes || offset < 0) throw failure("MEDIA_METADATA_UNAVAILABLE");
        // Charge failed transfers too; allow one transport recovery within this probe's budget.
        bytes += length;
        Range range;
        try { range=reader.read(offset,length); }
        catch(org.thornex.musicparty.dto.VideoFavorites.Failure error) {
            if(attempt==0 && !Thread.currentThread().isInterrupted() && deadline-System.nanoTime()>5_000_000_000L
                    && reader.prepareRetry(error))continue;
            throw error;
        }
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw failure("MEDIA_METADATA_UNAVAILABLE");
        if (range.bytes().length != length || range.totalLength() <= 0 || offset > range.totalLength()-length
                || total != -1 && total != range.totalLength()
                || validator != null && !Objects.equals(validator, range.validator())) throw failure("MEDIA_CONTENT_CHANGED");
        total = range.totalLength(); validator = range.validator(); return range.bytes();
      }
    }
    public Metadata probe(Reader reader) {
        return probe(reader,null);
    }
    public Metadata probe(Reader reader,Metadata cached) {
        total=-1;validator=null;
        byte[] head = read(reader,0,4096);
        if(cached!=null && validator!=null && validator.equals(cached.validator()) && total==cached.totalLength())
            return cached;
        var ranges = new MetadataRanges(reader, head);
        long offset = 0; byte[] moov = null; boolean ftyp = false, mdat = false;
        for (int count=0; offset<total && count<128; count++) {
            byte[] header = ranges.slice(offset,(int)Math.min(16,total-offset));
            if(header.length<8) throw failure("MP4_NOT_SUPPORTED");
            long size = u32(header,0); String type = text(header,4); int headerSize = 8;
            if(size==1) { if(header.length<16)throw failure("MP4_NOT_SUPPORTED");size=u64(header,8);headerSize=16; }
            if(size==0) size=total-offset;
            if(size<headerSize || size>total-offset) throw failure("MP4_NOT_SUPPORTED");
            if(type.equals("moof")) throw failure("MP4_NOT_SUPPORTED");
            ftyp |= type.equals("ftyp"); mdat |= type.equals("mdat");
            if(type.equals("moov")) {
                if(moov!=null || size>4*1024*1024 || size>Integer.MAX_VALUE) throw failure("MP4_NOT_SUPPORTED");
                moov=compactMetadata(ranges,offset+headerSize,offset+size,"moov");
            }
            offset+=size;
        }
        if(offset!=total || !ftyp || !mdat || moov==null) throw failure("MP4_NOT_SUPPORTED");
        List<Box> boxes=boxes(moov,0,moov.length);
        if(boxes.stream().anyMatch(b->b.type.equals("mvex") || b.type.equals("pssh"))) throw failure("MP4_NOT_SUPPORTED");
        long movieDuration=duration(moov,one(boxes,"mvhd"));
        int width=0,height=0,tracks=0; long videoDuration=0; boolean audio=false;
        for(Box trak:boxes) if(trak.type.equals("trak")) {
            if(++tracks>16) throw failure("MP4_NOT_SUPPORTED");
            List<Box> track=boxes(moov,trak.payload,trak.end);
            Box tkhd=one(track,"tkhd"),mdia=one(track,"mdia");
            List<Box> media=boxes(moov,mdia.payload,mdia.end);
            Box handler=one(media,"hdlr");require(handler,12);
            String kind=text(moov,handler.payload+8);
            long trackDuration=duration(moov,one(media,"mdhd"));
            Box minf=one(media,"minf");Box stbl=one(boxes(moov,minf.payload,minf.end),"stbl");
            List<Box> table=boxes(moov,stbl.payload,stbl.end);Box stsd=one(table,"stsd");require(stsd,8);
            if(u32(moov,stsd.payload+4)!=1) throw failure("MP4_NOT_SUPPORTED");
            List<Box> entries=boxes(moov,stsd.payload+8,stsd.end);if(entries.size()!=1) throw failure("MP4_NOT_SUPPORTED");
            Box sample=entries.getFirst();
            if(kind.equals("vide")) {
                if(width!=0 || !Set.of("avc1","hvc1").contains(sample.type)) throw failure("MP4_NOT_SUPPORTED");
                require(sample,78);require(tkhd,moov[tkhd.payload]==1?96:84);
                width=(int)(u32(moov,tkhd.end-8)>>>16);height=(int)(u32(moov,tkhd.end-4)>>>16);
                var extensions=boxes(moov,sample.payload+78,sample.end);
                if(sample.type.equals("avc1"))validateAvc(moov,one(extensions,"avcC"));
                else validateHevc(moov,one(extensions,"hvcC"));
                rejectProtection(extensions);videoDuration=trackDuration;
            } else if(kind.equals("soun")) {
                if(!sample.type.equals("mp4a")) throw failure("MP4_NOT_SUPPORTED");require(sample,28);
                if(u16(moov,sample.payload+8)!=0) throw failure("MP4_NOT_SUPPORTED");
                var extensions=boxes(moov,sample.payload+28,sample.end);Box esds=one(extensions,"esds");
                validateAac(moov,esds);rejectProtection(extensions);audio=true;
            }
            for(Box edit:track) if(edit.type.equals("edts")) {
                Box elst=one(boxes(moov,edit.payload,edit.end),"elst");require(elst,8);
                int version=moov[elst.payload]&255,entrySize=version==1?20:12;
                long count=u32(moov,elst.payload+4);
                if(version>1 || count<1 || count>2 || elst.end-elst.payload!=8+count*entrySize) throw failure("MP4_NOT_SUPPORTED");
                for(int i=0;i<count;i++){int p=elst.payload+8+i*entrySize;if(u16(moov,p+entrySize-4)!=1 || u16(moov,p+entrySize-2)!=0)throw failure("MP4_NOT_SUPPORTED");}
            }
        }
        if(!audio || width<=0 || height<=0 || videoDuration<=0 || Math.abs(movieDuration-videoDuration)>10000) throw failure("MP4_NOT_SUPPORTED");
        return new Metadata(movieDuration,width,height,total,validator);
    }
    /** Small windows keep large stsz/stco/stts sample tables out of the network budget. */
    private final class MetadataRanges {
        private final Reader reader;
        private final byte[] prefix;
        private byte[] window = new byte[0];
        private long windowOffset;
        private MetadataRanges(Reader reader,byte[] prefix){this.reader=reader;this.prefix=prefix;}
        private byte[] slice(long offset,int length){
            if(offset<0 || length<1 || offset>total-length)throw failure("MP4_NOT_SUPPORTED");
            if(offset<=prefix.length-length)return Arrays.copyOfRange(prefix,(int)offset,(int)offset+length);
            if(offset<windowOffset || offset-windowOffset>window.length-length){
                windowOffset=offset;
                window=read(reader,offset,(int)Math.max(length,Math.min(4096,total-offset)));
            }
            int start=(int)(offset-windowOffset);return Arrays.copyOfRange(window,start,start+length);
        }
    }
    /** Retain the same validated codec/timing structures while walking every sibling's bounds. */
    private static byte[] compactMetadata(MetadataRanges ranges,long start,long end,String parent){
        Set<String> wanted=switch(parent){
            case "moov" -> Set.of("mvhd","trak");
            case "trak" -> Set.of("tkhd","mdia","edts");
            case "mdia" -> Set.of("mdhd","hdlr","minf");
            case "minf" -> Set.of("stbl");
            case "stbl" -> Set.of("stsd");
            case "edts" -> Set.of("elst");
            default -> throw failure("MP4_NOT_SUPPORTED");
        };
        var result=new java.io.ByteArrayOutputStream();int count=0;
        for(long offset=start;offset<end;){
            if(end-offset<8 || ++count>128)throw failure("MP4_NOT_SUPPORTED");
            byte[] header=ranges.slice(offset,(int)Math.min(16,end-offset));
            long size=u32(header,0);String type=text(header,4);int headerSize=8;
            if(size==1){if(header.length<16)throw failure("MP4_NOT_SUPPORTED");size=u64(header,8);headerSize=16;}
            if(size<headerSize || size>end-offset)throw failure("MP4_NOT_SUPPORTED");
            if(Set.of("mvex","moof","pssh","sinf","encv","enca").contains(type))throw failure("MP4_NOT_SUPPORTED");
            if(wanted.contains(type)){
                byte[] body=Set.of("trak","mdia","minf","stbl","edts").contains(type)
                    ?compactMetadata(ranges,offset+headerSize,offset+size,type)
                    :ranges.slice(offset+headerSize,(int)size-headerSize);
                result.writeBytes(ByteBuffer.allocate(8).putInt(body.length+8).put(type.getBytes(StandardCharsets.US_ASCII)).array());
                result.writeBytes(body);
            }
            offset+=size;
        }
        return result.toByteArray();
    }
    private static void rejectProtection(List<Box> boxes){if(boxes.stream().anyMatch(b->Set.of("sinf","pssh","encv","enca").contains(b.type)))throw failure("MP4_NOT_SUPPORTED");}
    private static void validateAvc(byte[] data,Box box){
        require(box,7);if((data[box.payload]&255)!=1)throw failure("MP4_NOT_SUPPORTED");
        int count=data[box.payload+5]&31,p=box.payload+6;if(count==0)throw failure("MP4_NOT_SUPPORTED");
        for(int group=0;group<2;group++){
            if(group==1){if(p>=box.end)throw failure("MP4_NOT_SUPPORTED");count=data[p++]&255;if(count==0)throw failure("MP4_NOT_SUPPORTED");}
            for(int n=0;n<count;n++){if(box.end-p<2)throw failure("MP4_NOT_SUPPORTED");int size=u16(data,p);p+=2;if(size<1 || size>box.end-p || (data[p]&31)!=(group==0?7:8))throw failure("MP4_NOT_SUPPORTED");p+=size;}
        }
    }
    /** hvc1 carries complete VPS/SPS/PPS in its version-1 decoder configuration. */
    private static void validateHevc(byte[] data,Box box){
        require(box,23);int start=box.payload;
        if(data[start]!=1 || (data[start+1]&0xc0)!=0 || !Set.of(1,2).contains(data[start+1]&31)
                || (data[start+13]&0xf0)!=0xf0 || (data[start+15]&0xfc)!=0xfc || (data[start+16]&0xfc)!=0xfc
                || (data[start+17]&0xf8)!=0xf8 || (data[start+18]&0xf8)!=0xf8 || (data[start+16]&3)!=1
                || !Set.of(0,2).contains(data[start+17]&7) || !Set.of(0,2).contains(data[start+18]&7)
                || (data[start+21]&3)==2)throw failure("MP4_NOT_SUPPORTED");
        int arrays=data[start+22]&255,p=start+23;Set<Integer> seen=new HashSet<>();
        if(arrays<3 || arrays>16)throw failure("MP4_NOT_SUPPORTED");
        for(int array=0;array<arrays;array++){
            if(box.end-p<3)throw failure("MP4_NOT_SUPPORTED");int header=data[p++]&255,type=header&63,count=u16(data,p);p+=2;
            if((header&64)!=0 || !Set.of(32,33,34,39,40).contains(type) || !seen.add(type) || count<1 || count>64
                    || type<=34&&(header&128)==0)throw failure("MP4_NOT_SUPPORTED");
            for(int unit=0;unit<count;unit++){
                if(box.end-p<2)throw failure("MP4_NOT_SUPPORTED");int length=u16(data,p);p+=2;
                if(length<2 || length>box.end-p || (data[p]&128)!=0 || ((data[p]&126)>>>1)!=type
                        || (data[p+1]&7)==0)throw failure("MP4_NOT_SUPPORTED");p+=length;
            }
        }
        if(p!=box.end || !seen.containsAll(Set.of(32,33,34)))throw failure("MP4_NOT_SUPPORTED");
    }
    private record Descriptor(int tag,int payload,int end){}
    private static Descriptor descriptor(byte[] data,int p,int end){
        if(p>=end)throw failure("MP4_NOT_SUPPORTED");int tag=data[p++]&255,size=0;boolean done=false;
        for(int n=0;n<4 && p<end;n++){int value=data[p++]&255;size=(size<<7)|(value&127);if((value&128)==0){done=true;break;}}
        if(!done || size>end-p)throw failure("MP4_NOT_SUPPORTED");return new Descriptor(tag,p,p+size);
    }
    private static void validateAac(byte[] data,Box box){
        require(box,8);Descriptor es=descriptor(data,box.payload+4,box.end);
        if(es.tag!=3 || es.end-es.payload<3 || data[es.payload+2]!=0)throw failure("MP4_NOT_SUPPORTED");
        Descriptor decoder=descriptor(data,es.payload+3,es.end);
        if(decoder.tag!=4 || decoder.end-decoder.payload<13 || (data[decoder.payload]&255)!=0x40 || ((data[decoder.payload+1]&255)>>>2)!=5)throw failure("MP4_NOT_SUPPORTED");
        Descriptor config=descriptor(data,decoder.payload+13,decoder.end);
        if(config.tag!=5 || config.end-config.payload<2)throw failure("MP4_NOT_SUPPORTED");
        int object=(data[config.payload]&255)>>>3;int rate=((data[config.payload]&7)<<1)|((data[config.payload+1]&255)>>>7);int channels=((data[config.payload+1]&255)>>>3)&15;
        if(!Set.of(2,5,29).contains(object) || rate>12 || channels<1 || channels>7)throw failure("MP4_NOT_SUPPORTED");
    }
    private static List<Box> boxes(byte[] data,int start,int end){
        List<Box> result=new ArrayList<>();
        for(int p=start;p<end;){if(end-p<8 || result.size()>=128)throw failure("MP4_NOT_SUPPORTED");long size=u32(data,p);int h=8;if(size==1){if(end-p<16)throw failure("MP4_NOT_SUPPORTED");size=u64(data,p+8);h=16;}if(size<h || size>end-p)throw failure("MP4_NOT_SUPPORTED");result.add(new Box(text(data,p+4),p+h,p+(int)size));p+=(int)size;}
        return result;
    }
    private static Box one(List<Box> boxes,String type){var values=boxes.stream().filter(b->b.type.equals(type)).toList();if(values.size()!=1)throw failure("MP4_NOT_SUPPORTED");return values.getFirst();}
    private static void require(Box box,int length){if(box.end-box.payload<length)throw failure("MP4_NOT_SUPPORTED");}
    private static long duration(byte[] data,Box box){require(box,20);int version=data[box.payload]&255;int p=box.payload+(version==1?20:12);require(box,version==1?32:20);if(version>1)throw failure("MP4_NOT_SUPPORTED");long scale=u32(data,p),ticks=version==1?u64(data,p+4):u32(data,p+4);if(scale==0 || ticks<=0 || ticks>Long.MAX_VALUE/1000)throw failure("MEDIA_METADATA_UNAVAILABLE");long ms=ticks*1000/scale;if(ms<=0)throw failure("MEDIA_METADATA_UNAVAILABLE");return ms;}
    private static int u16(byte[] b,int p){return ByteBuffer.wrap(b,p,2).order(ByteOrder.BIG_ENDIAN).getShort()&65535;}
    private static long u32(byte[] b,int p){return Integer.toUnsignedLong(ByteBuffer.wrap(b,p,4).order(ByteOrder.BIG_ENDIAN).getInt());}
    private static long u64(byte[] b,int p){long v=ByteBuffer.wrap(b,p,8).order(ByteOrder.BIG_ENDIAN).getLong();if(v<0)throw failure("MP4_NOT_SUPPORTED");return v;}
    private static String text(byte[] b,int p){return new String(b,p,4,StandardCharsets.US_ASCII);}
}
