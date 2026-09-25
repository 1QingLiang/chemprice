package com.datamarket.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping(value = {
        "/",
        "/login",
        "/dashboard",
        "/price-table",
        "/commodities",
        "/commodity/{id}",
        "/movers",
        "/user-manage",
        "/audit-log"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
