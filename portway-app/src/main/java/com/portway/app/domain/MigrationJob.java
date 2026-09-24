package com.portway.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "migration_job")
public class MigrationJob {

  @Id private UUID id;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private JobStatus status;

  @Column(name = "current_stage")
  private String currentStage;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> options = new LinkedHashMap<>();

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> stats = new LinkedHashMap<>();

  @Column(name = "error_message")
  private String errorMessage;

  private String verifier;

  @Enumerated(EnumType.STRING)
  @Column(name = "compile_status", nullable = false)
  private CompileStatus compileStatus = CompileStatus.NOT_RUN;

  protected MigrationJob() {}

  public MigrationJob(String name, Map<String, Object> options) {
    this.id = UUID.randomUUID();
    this.name = name;
    this.status = JobStatus.PENDING;
    this.createdAt = Instant.now();
    this.options = new LinkedHashMap<>(options);
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public JobStatus getStatus() {
    return status;
  }

  public void setStatus(JobStatus status) {
    this.status = status;
  }

  public String getCurrentStage() {
    return currentStage;
  }

  public void setCurrentStage(String currentStage) {
    this.currentStage = currentStage;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  public Map<String, Object> getOptions() {
    return options;
  }

  public Map<String, Object> getStats() {
    return stats;
  }

  public void setStats(Map<String, Object> stats) {
    this.stats = new LinkedHashMap<>(stats);
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public String getVerifier() {
    return verifier;
  }

  public void setVerifier(String verifier) {
    this.verifier = verifier;
  }

  public CompileStatus getCompileStatus() {
    return compileStatus;
  }

  public void setCompileStatus(CompileStatus compileStatus) {
    this.compileStatus = compileStatus;
  }
}
