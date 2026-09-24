package com.portway.app.api;

import com.portway.app.api.Dtos.FileDetail;
import com.portway.app.api.Dtos.FileSummary;
import com.portway.app.api.Dtos.FindingGroup;
import com.portway.app.api.Dtos.FindingView;
import com.portway.app.api.Dtos.JobDetail;
import com.portway.app.api.Dtos.JobSummary;
import com.portway.app.api.Dtos.ManualItem;
import com.portway.app.api.Dtos.MethodView;
import com.portway.app.api.Dtos.PageResponse;
import com.portway.app.api.Dtos.Report;
import com.portway.app.api.Dtos.ReviewProgress;
import com.portway.app.api.Dtos.ReviewRequest;
import com.portway.app.api.Dtos.StageTiming;
import com.portway.app.config.PortwayProperties;
import com.portway.app.domain.CompileStatus;
import com.portway.app.domain.FindingEntity;
import com.portway.app.domain.GeneratedFileEntity;
import com.portway.app.domain.JobStatus;
import com.portway.app.domain.MigratedMethod;
import com.portway.app.domain.MigrationJob;
import com.portway.app.domain.ReviewStatus;
import com.portway.app.domain.SourceFileEntity;
import com.portway.app.pipeline.JobContext;
import com.portway.app.pipeline.JobEvent;
import com.portway.app.pipeline.JobEvents;
import com.portway.app.pipeline.JobNotFoundException;
import com.portway.app.pipeline.JobProgress;
import com.portway.app.pipeline.PipelineOrchestrator;
import com.portway.app.pipeline.SampleProject;
import com.portway.app.pipeline.ZipIngest;
import com.portway.app.repo.FindingRepository;
import com.portway.app.repo.GeneratedFileRepository;
import com.portway.app.repo.JobStageRepository;
import com.portway.app.repo.MigratedMethodRepository;
import com.portway.app.repo.MigrationJobRepository;
import com.portway.app.repo.SourceFileRepository;
import com.portway.core.generate.MigrationOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Everything the REST API does to jobs, kept out of the controller so it can be tested alone. */
@Service
public class JobService {

  private static final Pattern PACKAGE = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*");
  private static final List<String> SEVERITY_ORDER = List.of("HIGH", "MEDIUM", "LOW", "INFO");

  private final PortwayProperties properties;
  private final ZipIngest zipIngest;
  private final SampleProject sample;
  private final PipelineOrchestrator orchestrator;
  private final JobProgress progress;
  private final JobEvents events;
  private final MigrationJobRepository jobs;
  private final JobStageRepository stages;
  private final SourceFileRepository sources;
  private final GeneratedFileRepository files;
  private final MigratedMethodRepository methods;
  private final FindingRepository findings;

  public JobService(
      PortwayProperties properties,
      ZipIngest zipIngest,
      SampleProject sample,
      PipelineOrchestrator orchestrator,
      JobProgress progress,
      JobEvents events,
      MigrationJobRepository jobs,
      JobStageRepository stages,
      SourceFileRepository sources,
      GeneratedFileRepository files,
      MigratedMethodRepository methods,
      FindingRepository findings) {
    this.properties = properties;
    this.zipIngest = zipIngest;
    this.sample = sample;
    this.orchestrator = orchestrator;
    this.progress = progress;
    this.events = events;
    this.jobs = jobs;
    this.stages = stages;
    this.sources = sources;
    this.files = files;
    this.methods = methods;
    this.findings = findings;
  }

  /** User-chosen migration settings, validated before any work starts. */
  public record JobOptions(String basePackage, boolean keepInterfacePrefix, boolean ai) {

    public JobOptions {
      if (basePackage != null && basePackage.isBlank()) {
        basePackage = null;
      }
      if (basePackage != null && !PACKAGE.matcher(basePackage).matches()) {
        throw new InvalidRequestException("Not a valid Java package name: " + basePackage);
      }
    }

    MigrationOptions toMigrationOptions() {
      return MigrationOptions.defaults().withBasePackage(basePackage).withKeepInterfacePrefix(keepInterfacePrefix);
    }

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("basePackage", basePackage);
      map.put("keepInterfacePrefix", keepInterfacePrefix);
      map.put("ai", ai);
      return map;
    }
  }

  // ------------------------------------------------------------- commands

  public JobSummary createFromUpload(String filename, InputStream zip, JobOptions options) {
    String name = filename == null || filename.isBlank() ? "upload" : filename.replaceAll("(?i)\\.zip$", "");
    MigrationJob job = jobs.save(new MigrationJob(name, options.toMap()));
    Path workDir = workDir(job.getId());
    try {
      ZipIngest.Extracted extracted = zipIngest.extract(zip, workDir.resolve("source"));
      return start(job, workDir, extracted.root(), options);
    } catch (RuntimeException e) {
      jobs.delete(job);
      deleteQuietly(workDir);
      throw e;
    }
  }

  public JobSummary createFromSample(JobOptions options) {
    MigrationJob job = jobs.save(new MigrationJob(SampleProject.NAME, options.toMap()));
    Path workDir = workDir(job.getId());
    Path root = sample.copyTo(workDir.resolve("source"));
    return start(job, workDir, root, options);
  }

  private JobSummary start(MigrationJob job, Path workDir, Path sourceRoot, JobOptions options) {
    UUID id = job.getId();
    JobContext context =
        new JobContext(
            id, workDir, sourceRoot, options.toMigrationOptions(), options.ai(),
            stage -> progress.stageStarted(id, stage));
    orchestrator.submit(context);
    return summary(job);
  }

  @Transactional
  public FileSummary review(UUID jobId, UUID fileId, ReviewRequest request) {
    requireState(jobId, JobStatus.COMPLETED, "reviewed");
    GeneratedFileEntity file = file(jobId, fileId);
    file.setReviewStatus(request.status());
    if (request.editedContent() != null && !request.editedContent().equals(file.getContent())) {
      file.setContent(request.editedContent());
      // An edit has not been compiled. Say so until someone re-runs verification.
      file.setCompileStatus(CompileStatus.NOT_RUN);
    }
    return fileSummary(file, findings.findByGeneratedFileId(fileId), sourcePathOf(file));
  }

  @Transactional
  public void delete(UUID jobId) {
    MigrationJob job = job(jobId);
    if (job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.PENDING) {
      throw new JobStateException("Job " + jobId + " is still running and cannot be deleted yet");
    }
    jobs.delete(job);
    deleteQuietly(workDir(jobId));
  }

  // -------------------------------------------------------------- queries

  @Transactional(readOnly = true)
  public PageResponse<JobSummary> list(int page, int size) {
    Page<MigrationJob> result =
        jobs.findAll(PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
            Sort.by(Sort.Direction.DESC, "createdAt")));
    return new PageResponse<>(
        result.getContent().stream().map(JobService::summary).toList(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  @Transactional(readOnly = true)
  public JobDetail detail(UUID jobId) {
    MigrationJob job = job(jobId);
    List<StageTiming> timings =
        stages.findByJobIdOrderByIdAsc(jobId).stream()
            .map(s -> new StageTiming(
                s.getStage(),
                s.getStartedAt(),
                s.getCompletedAt(),
                s.getCompletedAt() == null ? null : Duration.between(s.getStartedAt(), s.getCompletedAt()).toMillis(),
                s.getDetail()))
            .toList();
    return new JobDetail(summary(job), job.getOptions(), job.getStats(), timings);
  }

  @Transactional(readOnly = true)
  public List<FileSummary> files(UUID jobId) {
    job(jobId);
    Map<UUID, List<FindingEntity>> byFile =
        findings.findByJobId(jobId).stream()
            .filter(f -> f.getGeneratedFileId() != null)
            .collect(Collectors.groupingBy(FindingEntity::getGeneratedFileId));
    Map<UUID, String> sourcePaths = sourcePaths(jobId);
    return files.findByJobIdOrderByPositionAsc(jobId).stream()
        .map(f -> fileSummary(f, byFile.getOrDefault(f.getId(), List.of()), sourcePaths.get(f.getSourceFileId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public FileDetail fileDetail(UUID jobId, UUID fileId) {
    GeneratedFileEntity file = file(jobId, fileId);
    SourceFileEntity source = file.getSourceFileId() == null ? null : sources.findById(file.getSourceFileId()).orElse(null);
    List<FindingEntity> fileFindings = findings.findByGeneratedFileId(fileId);
    List<MethodView> fileMethods =
        methods.findByGeneratedFileId(fileId).stream().map(JobService::methodView).toList();
    return new FileDetail(
        fileSummary(file, fileFindings, source == null ? null : source.getPath()),
        language(file.getPath()),
        file.getContent(),
        file.getOriginalContent(),
        source == null ? null : language(source.getPath()),
        source == null ? null : source.getContent(),
        fileFindings.stream()
            .sorted(Comparator.comparing((FindingEntity f) -> SEVERITY_ORDER.indexOf(f.getSeverity()))
                .thenComparing(f -> Objects.requireNonNullElse(f.getGeneratedLine(), 0)))
            .map(JobService::findingView)
            .toList(),
        fileMethods);
  }

  @Transactional(readOnly = true)
  public Report report(UUID jobId) {
    MigrationJob job = job(jobId);
    List<FindingEntity> all = findings.findByJobId(jobId);
    List<FindingGroup> groups =
        all.stream()
            .collect(Collectors.groupingBy(FindingEntity::getCode, LinkedHashMap::new, Collectors.toList()))
            .entrySet()
            .stream()
            .map(e -> new FindingGroup(
                e.getKey(),
                e.getValue().get(0).getSeverity(),
                e.getValue().size(),
                e.getValue().stream().map(JobService::findingView).toList()))
            .sorted(Comparator.comparing((FindingGroup g) -> SEVERITY_ORDER.indexOf(g.severity()))
                .thenComparing(FindingGroup::count, Comparator.reverseOrder()))
            .toList();

    Map<UUID, GeneratedFileEntity> filesById =
        files.findByJobIdOrderByPositionAsc(jobId).stream()
            .collect(Collectors.toMap(GeneratedFileEntity::getId, Function.identity()));
    List<ManualItem> manual =
        methods.findByJobId(jobId).stream()
            .filter(m -> m.getStrategy().equals("MANUAL_REQUIRED"))
            .map(m -> new ManualItem(
                m.getJavaSignature(),
                m.getReason(),
                m.getSourcePath(),
                m.getSourceLine(),
                m.getGeneratedFileId(),
                filesById.containsKey(m.getGeneratedFileId()) ? filesById.get(m.getGeneratedFileId()).getPath() : null))
            .toList();

    Map<ReviewStatus, Long> review =
        filesById.values().stream().collect(Collectors.groupingBy(GeneratedFileEntity::getReviewStatus, Collectors.counting()));
    return new Report(
        summary(job),
        job.getStats(),
        groups,
        manual,
        new ReviewProgress(
            review.getOrDefault(ReviewStatus.ACCEPTED, 0L),
            review.getOrDefault(ReviewStatus.REJECTED, 0L),
            review.getOrDefault(ReviewStatus.PENDING, 0L)));
  }

  /**
   * The generated project as it stands after review: edits included, rejected files left out.
   * A rejected file is one the reviewer decided should not ship.
   */
  @Transactional(readOnly = true)
  public void download(UUID jobId, OutputStream out) {
    requireState(jobId, JobStatus.COMPLETED, "downloaded");
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (GeneratedFileEntity file : files.findByJobIdOrderByPositionAsc(jobId)) {
        if (file.getReviewStatus() == ReviewStatus.REJECTED) {
          continue;
        }
        zip.putNextEntry(new ZipEntry(file.getPath()));
        zip.write(file.getContent().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Transactional(readOnly = true)
  public String downloadName(UUID jobId) {
    return job(jobId).getName().replaceAll("[^A-Za-z0-9._-]+", "-") + "-spring.zip";
  }

  /** Current contents of the files that would ship, for re-verification. */
  @Transactional(readOnly = true)
  public Map<String, String> currentFiles(UUID jobId) {
    requireState(jobId, JobStatus.COMPLETED, "verified");
    Map<String, String> current = new LinkedHashMap<>();
    for (GeneratedFileEntity file : files.findByJobIdOrderByPositionAsc(jobId)) {
      if (file.getReviewStatus() != ReviewStatus.REJECTED) {
        current.put(file.getPath(), file.getContent());
      }
    }
    return current;
  }

  public SseEmitter events(UUID jobId) {
    MigrationJob job = job(jobId);
    String type =
        switch (job.getStatus()) {
          case COMPLETED -> "completed";
          case FAILED -> "failed";
          default -> "snapshot";
        };
    return events.subscribe(
        jobId, JobEvent.of(jobId, type, job.getCurrentStage(), job.getStatus().name(), job.getErrorMessage()));
  }

  // -------------------------------------------------------------- helpers

  Path workDir(UUID jobId) {
    return properties.workDir().resolve("jobs").resolve(jobId.toString());
  }

  private MigrationJob job(UUID jobId) {
    return jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
  }

  private GeneratedFileEntity file(UUID jobId, UUID fileId) {
    return files.findByIdAndJobId(fileId, jobId)
        .orElseThrow(() -> new NotFoundException("No file " + fileId + " in job " + jobId));
  }

  private void requireState(UUID jobId, JobStatus status, String action) {
    MigrationJob job = job(jobId);
    if (job.getStatus() != status) {
      throw new JobStateException(
          "Job " + jobId + " is " + job.getStatus() + " and cannot be " + action + " until it is " + status);
    }
  }

  private Map<UUID, String> sourcePaths(UUID jobId) {
    return sources.findByJobIdOrderByPathAsc(jobId).stream()
        .collect(Collectors.toMap(SourceFileEntity::getId, SourceFileEntity::getPath));
  }

  private String sourcePathOf(GeneratedFileEntity file) {
    return file.getSourceFileId() == null
        ? null
        : sources.findById(file.getSourceFileId()).map(SourceFileEntity::getPath).orElse(null);
  }

  static JobSummary summary(MigrationJob job) {
    Map<String, Object> stats = job.getStats() == null ? Map.of() : job.getStats();
    @SuppressWarnings("unchecked")
    Map<String, Object> byStrategy = (Map<String, Object>) stats.get("methodsByStrategy");
    return new JobSummary(
        job.getId(),
        job.getName(),
        job.getStatus(),
        job.getCurrentStage(),
        job.getCreatedAt(),
        job.getStartedAt(),
        job.getCompletedAt(),
        job.getCompileStatus(),
        job.getVerifier(),
        job.getErrorMessage(),
        stats.get("files") instanceof Number n ? n.intValue() : null,
        stats.get("methods") instanceof Number n ? n.intValue() : null,
        byStrategy);
  }

  private static FileSummary fileSummary(GeneratedFileEntity file, List<FindingEntity> fileFindings, String sourcePath) {
    String highest =
        fileFindings.stream()
            .map(FindingEntity::getSeverity)
            .min(Comparator.comparingInt(SEVERITY_ORDER::indexOf))
            .orElse(null);
    return new FileSummary(
        file.getId(),
        file.getPath(),
        file.getCategory(),
        file.getStrategy(),
        file.getConfidence(),
        file.getCompileStatus(),
        file.getReviewStatus(),
        file.isEdited(),
        sourcePath,
        fileFindings.size(),
        highest);
  }

  private static FindingView findingView(FindingEntity f) {
    return new FindingView(
        f.getId(), f.getGeneratedFileId(), f.getSeverity(), f.getCode(), f.getMessage(), f.getSourcePath(),
        f.getSourceLine(), f.getGeneratedLine());
  }

  private static MethodView methodView(MigratedMethod m) {
    return new MethodView(
        m.getMethodKey(), m.getJavaSignature(), m.getStrategy(), m.getTier(), m.getReason(), m.getSourcePath(),
        m.getSourceLine(), m.getSourceEndLine());
  }

  /** Monaco language ids. */
  static String language(String path) {
    String p = path.toLowerCase(java.util.Locale.ROOT);
    if (p.endsWith(".java")) return "java";
    if (p.endsWith(".cs")) return "csharp";
    if (p.endsWith(".xml") || p.endsWith(".csproj")) return "xml";
    if (p.endsWith(".yml") || p.endsWith(".yaml")) return "yaml";
    if (p.endsWith(".json")) return "json";
    if (p.endsWith(".md")) return "markdown";
    return "plaintext";
  }

  static void deleteQuietly(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // best effort: a leftover temp directory is not worth failing a request over
    }
  }
}
