package com.portway.app.config;

import com.portway.app.verify.DockerCompileVerifier;
import com.portway.app.verify.VerifyClasspath;
import com.portway.core.verify.CompileVerifier;
import com.portway.core.verify.InProcessCompileVerifier;
import java.nio.file.Path;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@EnableConfigurationProperties(PortwayProperties.class)
public class PipelineConfig {

  /**
   * Migrations run here, never on a request thread. Bounded on purpose: a full queue rejects the
   * upload with 503 rather than letting work pile up behind a slow compile.
   */
  @Bean(name = "pipelineExecutor")
  ThreadPoolTaskExecutor pipelineExecutor(PortwayProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.pipeline().coreThreads());
    executor.setMaxPoolSize(properties.pipeline().maxThreads());
    executor.setQueueCapacity(properties.pipeline().queueCapacity());
    executor.setThreadNamePrefix("pipeline-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
  }

  /** The in-process verifier: no Docker needed, weaker guarantee, stated in its name. */
  @Bean
  @ConditionalOnProperty(name = "portway.verify.mode", havingValue = "in-process", matchIfMissing = true)
  CompileVerifier inProcessVerifier() {
    return new InProcessCompileVerifier(VerifyClasspath.resolve());
  }

  /** A real Maven build in a network-isolated container. */
  @Bean
  @ConditionalOnProperty(name = "portway.verify.mode", havingValue = "docker")
  CompileVerifier dockerVerifier(PortwayProperties properties) {
    PortwayProperties.Verify.Docker docker = properties.verify().docker();
    Path cache = docker == null || docker.cacheDir() == null
        ? Path.of(System.getProperty("user.home"), ".portway", "m2")
        : docker.cacheDir();
    return new DockerCompileVerifier(
        cache,
        docker == null ? java.time.Duration.ofSeconds(180) : docker.timeout(),
        docker == null ? 1024L * 1024 * 1024 : docker.memoryBytes());
  }
}
