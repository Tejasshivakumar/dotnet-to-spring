package com.portway.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.portway.app.domain.*;
import com.portway.app.pipeline.JobEvents;
import com.portway.app.repo.*;
import com.portway.core.verify.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ReverifyServiceTest {
  private final JobService jobService = mock(JobService.class);
  private final CompileVerifier verifier = mock(CompileVerifier.class);
  private final TaskExecutor executor = mock(TaskExecutor.class);
  private final MigrationJobRepository jobs = mock(MigrationJobRepository.class);
  private final GeneratedFileRepository files = mock(GeneratedFileRepository.class);
  private final FindingRepository findings = mock(FindingRepository.class);
  private final JobEvents events = mock(JobEvents.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final MigrationJob job = new MigrationJob("test", Map.of());
  private final GeneratedFileEntity file = new GeneratedFileEntity(job.getId(), null,
      "src/main/java/Example.java", 0, "class Example {}", "RULE_MAPPED", BigDecimal.ONE, CompileStatus.PASS);
  private final AtomicReference<Runnable> queued = new AtomicReference<>();
  private ReverifyService service;

  @BeforeEach
  void setUp() {
    job.setStatus(JobStatus.COMPLETED);
    job.setCompileStatus(CompileStatus.PASS);
    when(jobs.findForUpdate(job.getId())).thenReturn(Optional.of(job));
    when(files.findByJobIdOrderByPositionAsc(job.getId())).thenReturn(List.of(file));
    when(jobService.currentFiles(job.getId())).thenAnswer(invocation -> {
      if (job.getStatus() != JobStatus.COMPLETED) throw new JobStateException("Already running");
      return Map.of(file.getPath(), file.getContent());
    });
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    doAnswer(invocation -> { queued.set(invocation.getArgument(0)); return null; }).when(executor).execute(any());
    service = new ReverifyService(jobService, verifier, executor, jobs, files, findings, events, transactions);
  }

  @Test
  void marksRunningBeforeDispatchAndRejectsDuplicateVerification() {
    when(verifier.verify(any())).thenReturn(new CompileResult(true, List.of(), "", Duration.ZERO, "test"));
    service.submit(job.getId());
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(job.getCompileStatus()).isEqualTo(CompileStatus.NOT_RUN);
    assertThat(file.getCompileStatus()).isEqualTo(CompileStatus.NOT_RUN);
    assertThatThrownBy(() -> service.submit(job.getId())).isInstanceOf(JobStateException.class);
    verify(executor, times(1)).execute(any());
    queued.get().run();
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getCompileStatus()).isEqualTo(CompileStatus.PASS);
    verify(events).publish(argThat(event -> event.terminal()));
  }

  @Test
  void queueRejectionLeavesTheJobReviewableAndRetryable() {
    doThrow(new TaskRejectedException("full")).when(executor).execute(any());
    assertThatThrownBy(() -> service.submit(job.getId())).isInstanceOf(TaskRejectedException.class);
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getCompileStatus()).isEqualTo(CompileStatus.NOT_RUN);
  }

  @Test
  void verifierExceptionIsPersistedAndDoesNotLeaveAStuckJob() {
    when(verifier.verify(any())).thenThrow(new IllegalStateException("Docker unavailable"));
    when(verifier.name()).thenReturn("test");
    service.submit(job.getId());
    queued.get().run();
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getCompileStatus()).isEqualTo(CompileStatus.FAIL);
    assertThat(file.getCompileStatus()).isEqualTo(CompileStatus.NOT_RUN);
    verify(findings).save(argThat(f -> f.getMessage().contains("Docker unavailable")));
    verify(events).publish(argThat(event -> event.terminal()));
  }

  @Test
  void globalBuildFailureDoesNotMarkEveryFileAsPassing() {
    when(verifier.verify(any())).thenReturn(new CompileResult(false,
        List.of(new CompileError(null, 0, 0, "Dependency resolution failed")), "", Duration.ZERO, "test"));
    service.submit(job.getId());
    queued.get().run();
    assertThat(job.getCompileStatus()).isEqualTo(CompileStatus.FAIL);
    assertThat(file.getCompileStatus()).isEqualTo(CompileStatus.NOT_RUN);
  }
}
