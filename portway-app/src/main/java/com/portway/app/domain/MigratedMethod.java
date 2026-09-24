package com.portway.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "migrated_method")
public class MigratedMethod {

  @Id private UUID id;

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Column(name = "generated_file_id")
  private UUID generatedFileId;

  @Column(name = "method_key", nullable = false)
  private String methodKey;

  @Column(name = "java_signature", nullable = false)
  private String javaSignature;

  @Column(nullable = false)
  private String strategy;

  private String tier;

  private String reason;

  @Column(name = "source_path")
  private String sourcePath;

  @Column(name = "source_line")
  private Integer sourceLine;

  @Column(name = "source_end_line")
  private Integer sourceEndLine;

  protected MigratedMethod() {}

  public MigratedMethod(
      UUID jobId,
      UUID generatedFileId,
      String methodKey,
      String javaSignature,
      String strategy,
      String tier,
      String reason,
      String sourcePath,
      Integer sourceLine,
      Integer sourceEndLine) {
    this.id = UUID.randomUUID();
    this.jobId = jobId;
    this.generatedFileId = generatedFileId;
    this.methodKey = methodKey;
    this.javaSignature = javaSignature;
    this.strategy = strategy;
    this.tier = tier;
    this.reason = reason;
    this.sourcePath = sourcePath;
    this.sourceLine = sourceLine;
    this.sourceEndLine = sourceEndLine;
  }

  public UUID getId() {
    return id;
  }

  public UUID getGeneratedFileId() {
    return generatedFileId;
  }

  public String getMethodKey() {
    return methodKey;
  }

  public String getJavaSignature() {
    return javaSignature;
  }

  public String getStrategy() {
    return strategy;
  }

  public String getTier() {
    return tier;
  }

  public String getReason() {
    return reason;
  }

  public String getSourcePath() {
    return sourcePath;
  }

  public Integer getSourceLine() {
    return sourceLine;
  }

  public Integer getSourceEndLine() {
    return sourceEndLine;
  }
}
