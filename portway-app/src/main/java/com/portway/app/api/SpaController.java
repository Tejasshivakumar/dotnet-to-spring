package com.portway.app.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The React app routes on the client. A reload or a shared link on {@code /jobs/…} reaches the
 * server first, which hands back the app's index.html and lets the router take over.
 */
@Controller
public class SpaController {

  @GetMapping({"/jobs/**"})
  public String app() {
    return "forward:/index.html";
  }
}
