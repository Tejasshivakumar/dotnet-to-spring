package com.portway.app.api;

import com.portway.app.domain.CompileStatus;
import com.portway.app.domain.FileCategory;
import com.portway.app.domain.JobStatus;
import com.portway.app.domain.ReviewStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The API's request and response shapes. Records only: no entity ever leaves the service layer. */
public final class Dtos {

  private Dtos() {}

  public record JobSummary(
      UUID id,
      String name,
      JobStatus status,
      String currentStage,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt,
      CompileStatus compileStatus,
      String verifier,
      String errorMessage,
      Integer files,
      Integer methods,
      Map<String, Object> methodsByStrategy) {}

  public record StageTiming(String stage, Instant startedAt, Instant completedAt, Long millis, String detail) {}

  public record JobDetail(
      JobSummary job, Map<String, Object> options, Map<String, Object> stats, List<StageTiming> stages) {}

  public record FileSummary(
      UUID id,
      String path,
      FileCategory category,
      String strategy,
      BigDecimal confidence,
      CompileStatus compileStatus,
      ReviewStatus reviewStatus,
      boolean edited,
      String sourcePath,
      int findings,
      String highestSeverity) {}

  public record FindingView(
      UUID id,
      UUID fileId,
      String severity,
      String code,
      String message,
      String sourcePath,
      Integer sourceLine,
      Integer generatedLine) {}

  public record MethodView(
      String key,
      String javaSignature,
      String strategy,
      String tier,
      String reason,
      String sourcePath,
      Integer sourceLine,
      Integer sourceEndLine) {}

  public record FileDetail(
      FileSummary file,
      String language,
      String content,
      String originalContent,
      String sourceLanguage,
      String sourceContent,
      List<FindingView> findings,
      List<MethodView> methods) {}

  /** Accept, reject, or save an edit. An edit invalidates the file's compile status until re-verified. */
  public record ReviewRequest(
      @NotNull ReviewStatus status, @Size(max = 2_000_000) String editedContent) {}

  public record FindingGroup(String code, String severity, long count, List<FindingView> findings) {}

  public record ManualItem(
      String javaSignature, String reason, String sourcePath, Integer sourceLine, UUID fileId, String path) {}

  public record ReviewProgress(long accepted, long rejected, long pending) {}

  public record Report(
      JobSummary job,
      Map<String, Object> stats,
      List<FindingGroup> findings,
      List<ManualItem> manualItems,
      ReviewProgress review) {}

  public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}
}
