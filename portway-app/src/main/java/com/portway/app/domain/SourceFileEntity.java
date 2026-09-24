package com.portway.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "source_file")
public class SourceFileEntity {

  @Id private UUID id;

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Column(nullable = false)
  private String path;

  @Column(nullable = false)
  private String content;

  @Column(name = "detected_role")
  private String detectedRole;

  protected SourceFileEntity() {}

  public SourceFileEntity(UUID jobId, String path, String content) {
    this.id = UUID.randomUUID();
    this.jobId = jobId;
    this.path = path;
    this.content = content;
  }

  public UUID getId() {
    return id;
  }

  public UUID getJobId() {
    return jobId;
  }

  public String getPath() {
    return path;
  }

  public String getContent() {
    return content;
  }

  public String getDetectedRole() {
    return detectedRole;
  }

  public void setDetectedRole(String detectedRole) {
    this.detectedRole = detectedRole;
  }
}
