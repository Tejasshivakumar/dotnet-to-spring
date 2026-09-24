package com.portway.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** When one pipeline stage started and finished, for the progress tracker. */
@Entity
@Table(name = "job_stage")
public class JobStage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Column(nullable = false)
  private String stage;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  private String detail;

  protected JobStage() {}

  public JobStage(UUID jobId, String stage) {
    this.jobId = jobId;
    this.stage = stage;
    this.startedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public UUID getJobId() {
    return jobId;
  }

  public String getStage() {
    return stage;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public String getDetail() {
    return detail;
  }

  public void complete(String detail) {
    this.completedAt = Instant.now();
    this.detail = detail;
  }
}
