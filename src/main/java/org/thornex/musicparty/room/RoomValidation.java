package org.thornex.musicparty.room;

import java.util.regex.Pattern;

public final class RoomValidation {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private RoomValidation() {}
    public static boolean key(String key) { return key != null && key.matches("[\\x21-\\x7E]{8,16}"); }
    public static boolean name(String value) {
        if (value == null || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) return false;
        var matcher = GRAPHEME.matcher(value);
        int count = 0;
        while (matcher.find()) {
            if (++count > 16) return false;
            String cluster = matcher.group();
            int[] points=cluster.codePoints().toArray();
            boolean tagFlag=points.length>=3 && points[0]==0x1F3F4 && points[points.length-1]==0xE007F
                    && java.util.stream.IntStream.range(1,points.length-1).allMatch(i->points[i]>=0xE0061 && points[i]<=0xE007A);
            if (cluster.codePoints().allMatch(RoomValidation::mark)) return false;
            for(int i=0;i<points.length;i++) {
                int c=points[i];
                int type = Character.getType(c);
                if(type==Character.FORMAT) {
                    if(c==0x200D) {
                        int before=i-1;
                        while(before>=0 && (mark(points[before]) || Character.isEmojiModifier(points[before]))) before--;
                        if(before<0 || i+1>=points.length || !Character.isExtendedPictographic(points[before]) || !Character.isExtendedPictographic(points[i+1])) return false;
                    } else if(!tagFlag || c<0xE0061 || c>0xE007F) return false;
                } else if(type==Character.CONTROL || type==Character.SURROGATE || type==Character.LINE_SEPARATOR || type==Character.PARAGRAPH_SEPARATOR) return false;
            }
        }
        return count >= 2;
    }
    private static boolean mark(int c) { int type=Character.getType(c);return type==Character.NON_SPACING_MARK || type==Character.ENCLOSING_MARK || type==Character.COMBINING_SPACING_MARK; }
}
