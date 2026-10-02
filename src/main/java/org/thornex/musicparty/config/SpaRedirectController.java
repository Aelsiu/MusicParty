package org.thornex.musicparty.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SpaRedirectController {

    // Reserve only endpoint names: room IDs may start with ws, api or proxy.
    @RequestMapping(value = {"/{path:(?!ws$|api$|proxy$)[^.]+}","/{path:(?!ws$|api$|proxy$)[^.]+}/"})
    public String redirect() {
        return "forward:/index.html";
    }
}
