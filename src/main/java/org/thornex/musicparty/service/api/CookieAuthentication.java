package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

/** Only explicit platform authentication results may mark a configured credential expired. */
public final class CookieAuthentication {
    private CookieAuthentication() {}
    public enum Result { VALID, INVALID, UNKNOWN }
    public record Credential(String value, long revision) {
        public boolean configured() {
            return StringUtils.hasText(value) && !"YOUR_NETEASE_COOKIE_STRING_HERE".equals(value)
                    && !"YOUR_BILIBILI_COOKIE_STRING_HERE".equals(value);
        }
        @Override public String toString() {return "Credential[revision="+revision+", configured="+configured()+"]";}
    }
    private static Integer code(JsonNode json) {
        JsonNode value=json.path("code");
        return value.isIntegralNumber() && value.canConvertToInt()?value.intValue():null;
    }
    private static boolean positiveId(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue()>0;
    }
    public static Result netease(JsonNode json) {
        Integer code=code(json);
        if (code==null) return Result.UNKNOWN;
        if (code==301) return Result.INVALID;
        if (code!=200) return Result.UNKNOWN;
        if (json.path("profile").isObject() && positiveId(json.path("profile").path("userId"))
                || json.path("account").isObject() && positiveId(json.path("account").path("id"))) return Result.VALID;
        if (json.has("profile") && json.get("profile").isNull() && json.has("account") && json.get("account").isNull()) return Result.INVALID;
        return Result.UNKNOWN;
    }
    public static Result bilibili(JsonNode json) {
        Integer code=code(json);
        if (code==null) return Result.UNKNOWN;
        if (code==-101) return Result.INVALID;
        if (code!=0 || !json.path("data").path("isLogin").isBoolean()) return Result.UNKNOWN;
        return json.path("data").path("isLogin").asBoolean()?Result.VALID:Result.INVALID;
    }
}
