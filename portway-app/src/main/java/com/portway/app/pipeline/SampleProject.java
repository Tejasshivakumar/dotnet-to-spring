package com.portway.app.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The bundled sample ASP.NET Core project. It is why a demo, or a recruiter following the link,
 * gets a result in one click without finding a .NET project first.
 */
@Component
public class SampleProject {

  static final String ROOT = "sample/BookstoreApi/";

  public static final String NAME = "BookstoreApi (sample)";

  /** Copies the sample into {@code target} and returns the project root. */
  public Path copyTo(Path target) {
    try {
      Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:" + ROOT + "**/*");
      int copied = 0;
      for (Resource resource : resources) {
        if (!resource.isReadable()) {
          continue;
        }
        String url = resource.getURL().toString();
        int at = url.lastIndexOf(ROOT);
        if (at < 0 || url.endsWith("/")) {
          continue;
        }
        Path destination = target.resolve(url.substring(at + ROOT.length())).normalize();
        if (!destination.startsWith(target)) {
          continue;
        }
        Files.createDirectories(destination.getParent());
        try (InputStream in = resource.getInputStream()) {
          Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        copied++;
      }
      if (copied == 0) {
        throw new IllegalStateException("The sample project is not bundled with this build");
      }
      return target;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot copy the sample project", e);
    }
  }
}
