package org.thornex.musicparty.room;

import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Scope(value = "room", proxyMode = ScopedProxyMode.TARGET_CLASS)
public @interface RoomScoped {}
