package com.portway.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "generated_file")
public class GeneratedFileEntity {

  @Id private UUID id;

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Column(name = "source_file_id")
  private UUID sourceFileId;

  @Column(nullable = false)
  private String path;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private FileCategory category;

  @Column(nullable = false)
  private int position;

  @Column(name = "original_content", nullable = false)
  private String originalContent;

  @Column(nullable = false)
  private String content;

  @Column(nullable = false)
  private String strategy;

  @Column(nullable = false, precision = 3, scale = 2)
  private BigDecimal confidence;

  @Enumerated(EnumType.STRING)
  @Column(name = "compile_status", nullable = false)
  private CompileStatus compileStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "review_status", nullable = false)
  private ReviewStatus reviewStatus = ReviewStatus.PENDING;

  protected GeneratedFileEntity() {}

  public GeneratedFileEntity(
      UUID jobId,
      UUID sourceFileId,
      String path,
      int position,
      String content,
      String strategy,
      BigDecimal confidence,
      CompileStatus compileStatus) {
    this.id = UUID.randomUUID();
    this.jobId = jobId;
    this.sourceFileId = sourceFileId;
    this.path = path;
    this.category = FileCategory.of(path);
    this.position = position;
    this.originalContent = content;
    this.content = content;
    this.strategy = strategy;
    this.confidence = confidence;
    this.compileStatus = compileStatus;
  }

  public UUID getId() {
    return id;
  }

  public UUID getJobId() {
    return jobId;
  }

  public UUID getSourceFileId() {
    return sourceFileId;
  }

  public String getPath() {
    return path;
  }

  public FileCategory getCategory() {
    return category;
  }

  public int getPosition() {
    return position;
  }

  public String getOriginalContent() {
    return originalContent;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public boolean isEdited() {
    return !content.equals(originalContent);
  }

  public String getStrategy() {
    return strategy;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public CompileStatus getCompileStatus() {
    return compileStatus;
  }

  public void setCompileStatus(CompileStatus compileStatus) {
    this.compileStatus = compileStatus;
  }

  public ReviewStatus getReviewStatus() {
    return reviewStatus;
  }

  public void setReviewStatus(ReviewStatus reviewStatus) {
    this.reviewStatus = reviewStatus;
  }
}
