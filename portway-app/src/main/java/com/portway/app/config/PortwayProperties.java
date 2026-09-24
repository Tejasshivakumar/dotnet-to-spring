package com.portway.app.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code portway:} in application.yml.
 *
 * @param workDir where uploaded sources and verification builds live, one directory per job
 */
@ConfigurationProperties(prefix = "portway")
public record PortwayProperties(
    Path workDir, Upload upload, Pipeline pipeline, Verify verify) {

  /** Limits on an uploaded zip. The zip is untrusted input. */
  public record Upload(
      @DefaultValue("2000") int maxEntries,
      @DefaultValue("104857600") long maxTotalBytes,
      @DefaultValue("5242880") long maxEntryBytes) {}

  /** The bounded executor that runs migrations. */
  public record Pipeline(
      @DefaultValue("2") int coreThreads,
      @DefaultValue("4") int maxThreads,
      @DefaultValue("10") int queueCapacity) {}

  public record Verify(@DefaultValue("in-process") String mode, Docker docker) {

    public record Docker(Path cacheDir, @DefaultValue("180s") Duration timeout, @DefaultValue("1073741824") long memoryBytes) {}
  }
}
