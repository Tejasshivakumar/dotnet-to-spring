package com.portway.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "finding")
public class FindingEntity {

  @Id private UUID id;

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Column(name = "generated_file_id")
  private UUID generatedFileId;

  @Column(nullable = false)
  private String severity;

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String message;

  @Column(name = "source_path")
  private String sourcePath;

  @Column(name = "source_line")
  private Integer sourceLine;

  @Column(name = "generated_line")
  private Integer generatedLine;

  protected FindingEntity() {}

  public FindingEntity(
      UUID jobId,
      UUID generatedFileId,
      String severity,
      String code,
      String message,
      String sourcePath,
      Integer sourceLine,
      Integer generatedLine) {
    this.id = UUID.randomUUID();
    this.jobId = jobId;
    this.generatedFileId = generatedFileId;
    this.severity = severity;
    this.code = code;
    this.message = message;
    this.sourcePath = sourcePath;
    this.sourceLine = sourceLine;
    this.generatedLine = generatedLine;
  }

  public UUID getId() {
    return id;
  }

  public UUID getGeneratedFileId() {
    return generatedFileId;
  }

  public String getSeverity() {
    return severity;
  }

  public String getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }

  public String getSourcePath() {
    return sourcePath;
  }

  public Integer getSourceLine() {
    return sourceLine;
  }

  public Integer getGeneratedLine() {
    return generatedLine;
  }
}
