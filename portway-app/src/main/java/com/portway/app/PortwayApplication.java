package com.portway.app;

import com.portway.app.ai.AiTranslatorFactory;
import com.portway.app.cli.PortwayCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PortwayApplication {

  public static void main(String[] args) {
    // `migrate` runs the pipeline from the command line and never starts the web
    // application, so it needs no database and no configuration.
    if (args.length > 0 && args[0].equals("migrate")) {
      PortwayCli cli = new PortwayCli(System.out, System.err, () -> AiTranslatorFactory.fromEnvironment().create());
      System.exit(cli.run(args));
    }
    SpringApplication.run(PortwayApplication.class, args);
  }
}
