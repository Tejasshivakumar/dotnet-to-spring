package com.portway.app.pipeline;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans pipeline progress out to every open SSE connection for a job.
 *
 * <p>Events are pushed, never buffered: a subscriber that connects late gets a snapshot of the
 * job's current state first, taken from the database, and then live events from there on.
 */
@Component
public class JobEvents {

  private static final Logger log = LoggerFactory.getLogger(JobEvents.class);
  private static final long TIMEOUT = Duration.ofMinutes(30).toMillis();

  private final Map<UUID, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

  public SseEmitter subscribe(UUID jobId, JobEvent snapshot) {
    SseEmitter emitter = new SseEmitter(TIMEOUT);
    List<SseEmitter> list = subscribers.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>());
    list.add(emitter);
    Runnable remove = () -> list.remove(emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(e -> remove.run());

    send(emitter, snapshot);
    if (snapshot.terminal()) {
      emitter.complete();
    }
    return emitter;
  }

  public void publish(JobEvent event) {
    List<SseEmitter> list = subscribers.getOrDefault(event.jobId(), List.of());
    for (SseEmitter emitter : list) {
      send(emitter, event);
      if (event.terminal()) {
        emitter.complete();
      }
    }
    if (event.terminal()) {
      subscribers.remove(event.jobId());
    }
  }

  private void send(SseEmitter emitter, JobEvent event) {
    try {
      emitter.send(SseEmitter.event().name(event.type()).data(event));
    } catch (IOException | IllegalStateException e) {
      // The client went away. Its emitter is removed by the error callback.
      log.debug("Dropping SSE subscriber for job {}: {}", event.jobId(), e.getMessage());
      emitter.completeWithError(e);
    }
  }
}
