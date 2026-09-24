package com.portway.app.api;

import com.portway.app.api.Dtos.FileDetail;
import com.portway.app.api.Dtos.FileSummary;
import com.portway.app.api.Dtos.JobDetail;
import com.portway.app.api.Dtos.JobSummary;
import com.portway.app.api.Dtos.PageResponse;
import com.portway.app.api.Dtos.Report;
import com.portway.app.api.Dtos.ReviewRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Migration jobs")
public class JobController {

  private final JobService jobs;
  private final ReverifyService reverify;

  public JobController(JobService jobs, ReverifyService reverify) {
    this.jobs = jobs;
    this.reverify = reverify;
  }

  @Operation(summary = "Upload a zipped ASP.NET Core project and start migrating it")
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<JobSummary> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false) String basePackage,
      @RequestParam(defaultValue = "false") boolean keepInterfacePrefix,
      @RequestParam(defaultValue = "false") boolean ai) {
    JobService.JobOptions options = new JobService.JobOptions(basePackage, keepInterfacePrefix, ai);
    try (InputStream in = file.getInputStream()) {
      return accepted(jobs.createFromUpload(file.getOriginalFilename(), in, options));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Operation(summary = "Start migrating the bundled sample project")
  @PostMapping("/sample")
  public ResponseEntity<JobSummary> sample(
      @RequestParam(required = false) String basePackage,
      @RequestParam(defaultValue = "false") boolean keepInterfacePrefix,
      @RequestParam(defaultValue = "false") boolean ai) {
    return accepted(jobs.createFromSample(new JobService.JobOptions(basePackage, keepInterfacePrefix, ai)));
  }

  @Operation(summary = "List jobs, newest first")
  @GetMapping
  public PageResponse<JobSummary> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return jobs.list(page, size);
  }

  @Operation(summary = "Status, current stage, stage timings and stats")
  @GetMapping("/{id}")
  public JobDetail get(@PathVariable UUID id) {
    return jobs.detail(id);
  }

  @Operation(summary = "Progress as server-sent events: stage, stage-complete, then completed or failed")
  @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable UUID id) {
    return jobs.events(id);
  }

  @Operation(summary = "Generated files with strategy, confidence, compile and review status")
  @GetMapping("/{id}/files")
  public List<FileSummary> files(@PathVariable UUID id) {
    return jobs.files(id);
  }

  @Operation(summary = "One file: C# source, generated Java, findings and methods")
  @GetMapping("/{id}/files/{fileId}")
  public FileDetail file(@PathVariable UUID id, @PathVariable UUID fileId) {
    return jobs.fileDetail(id, fileId);
  }

  @Operation(summary = "Accept, reject, or save an edit to a generated file")
  @PostMapping("/{id}/files/{fileId}/review")
  public FileSummary review(
      @PathVariable UUID id, @PathVariable UUID fileId, @Valid @RequestBody ReviewRequest request) {
    return jobs.review(id, fileId, request);
  }

  @Operation(summary = "Re-run compile verification over the files as reviewed")
  @PostMapping("/{id}/verify")
  public ResponseEntity<Void> verify(@PathVariable UUID id) {
    reverify.submit(id);
    return ResponseEntity.accepted().build();
  }

  @Operation(summary = "The full migration report")
  @GetMapping("/{id}/report")
  public Report report(@PathVariable UUID id) {
    return jobs.report(id);
  }

  @Operation(summary = "The generated project as a zip, edits included and rejected files left out")
  @GetMapping(path = "/{id}/download", produces = "application/zip")
  public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id) {
    String name = jobs.downloadName(id);
    // Checked up front: once streaming starts, an error can no longer become a clean 409.
    jobs.currentFiles(id);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
        .contentType(MediaType.parseMediaType("application/zip"))
        .body(out -> jobs.download(id, out));
  }

  @Operation(summary = "Delete a job and everything it produced")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    jobs.delete(id);
    return ResponseEntity.noContent().build();
  }

  private static ResponseEntity<JobSummary> accepted(JobSummary job) {
    return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.id())).body(job);
  }
}
