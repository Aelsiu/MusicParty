package org.thornex.musicparty.service.api;

import org.thornex.musicparty.dto.VideoDanmakuSegment;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Frozen seg.so schema, verified against public BV1uW411f72n P1/P2 on 2026-10-09. */
public final class BilibiliDanmakuDecoder {
    public static final int MAX_BODY = 2 * 1024 * 1024;
    /** Safe counts only. No text, sender metadata or raw body is retained for diagnostics. */
    public record Statistics(int bodyBytes, int parsedEntries, int displayableEntries, int filteredEntries, int duplicateEntries) {}
    public record Decoded(VideoDanmakuSegment segment, Statistics statistics) {}
    public static VideoDanmakuSegment decode(byte[] body, int segment, long durationMs) {
        return decodeWithStatistics(body, segment, durationMs).segment();
    }
    public static Decoded decodeWithStatistics(byte[] body, int segment, long durationMs) {
        if (body.length > MAX_BODY) throw overBudget();
        if (segment < 1 || (long)(segment-1)*360000 >= durationMs) throw invalid();
        Reader root = new Reader(body, 0, body.length);
        Map<String,VideoDanmakuSegment.Entry> entries = new LinkedHashMap<>();
        int count = 0, filtered = 0, duplicates = 0;
        while (root.more()) {
            long tag = root.varint();
            if (tag == 10) {
                if (++count > 6000) throw overBudget();
                Reader row = root.message();
                // Protobuf omits scalar zero values. Segment bounds below still reject
                // zero in later segments and explicit negative/overflowing progress.
                long id = 0, progress = 0, mode = 0, font = 25, color = 0xffffff;
                Reader content = null;
                while (row.more()) {
                    long field = row.varint();
                    switch ((int)field) {
                        case 8 -> id = row.varint();
                        case 16 -> progress = row.varint();
                        case 24 -> mode = row.varint();
                        case 32 -> font = row.varint();
                        case 40 -> color = row.varint();
                        case 58 -> content = row.message();
                        default -> row.skip(field);
                    }
                }
                if (mode < 1 || mode > 6) { filtered++; continue; }
                String text = content == null ? null : content.contentText();
                if (text != null && text.codePointCount(0,text.length()) > 100) throw overBudget();
                if (id == 0 || progress < (long)(segment-1)*360000 || progress >= Math.min(durationMs,(long)segment*360000)
                    || color < 0 || color > 0xffffff || text == null) throw invalid();
                if (entries.putIfAbsent(Long.toUnsignedString(id), new VideoDanmakuSegment.Entry(Long.toUnsignedString(id),progress,(int)mode,
                    font <= 18 ? 18 : font >= 30 ? 30 : 25,(int)color,text)) != null) duplicates++;
            } else root.skip(tag);
        }
        var sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparingLong(VideoDanmakuSegment.Entry::progressMs));
        return new Decoded(new VideoDanmakuSegment(segment,sorted), new Statistics(body.length,count,sorted.size(),filtered,duplicates));
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("DANMAKU_INVALID_OR_OVER_BUDGET"); }
    private static IllegalArgumentException overBudget() { return new IllegalArgumentException("DANMAKU_OVER_BUDGET"); }
    private static final class Reader {
        final byte[] bytes; int position; final int end;
        Reader(byte[] bytes,int start,int end) { this.bytes=bytes;this.position=start;this.end=end; }
        boolean more() { return position < end; }
        long varint() {
            long result=0;
            for(int i=0;i<10;i++) {
                if(!more()) throw invalid(); int value=bytes[position++]&255;
                if(i==9 && value>1) throw invalid(); result |= (long)(value&127)<<(7*i);
                if((value&128)==0) return result;
            }
            throw invalid();
        }
        Reader message() { long length=varint(); if(length<0 || length>end-position) throw invalid();
            Reader result=new Reader(bytes,position,position+(int)length);position+=(int)length;return result; }
        String contentText() { Reader value=this; if(value.end-value.position>400) throw overBudget();
            try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes,value.position,value.end-value.position)).toString(); }
            catch(CharacterCodingException error) { throw invalid(); }
        }
        void skip(long tag) { if(tag<=0 || tag>>>3==0) throw invalid();
            switch((int)(tag&7)) {
                case 0 -> varint();
                case 1 -> advance(8);
                case 2 -> message();
                case 5 -> advance(4);
                default -> throw invalid(); // groups/nesting are not part of the frozen schema
            }
        }
        void advance(int length) { if(length>end-position) throw invalid(); position+=length; }
    }
}
