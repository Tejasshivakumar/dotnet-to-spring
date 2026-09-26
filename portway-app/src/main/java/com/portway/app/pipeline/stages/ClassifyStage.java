package com.portway.app.pipeline.stages;

import com.portway.app.domain.SourceFileEntity;
import com.portway.app.pipeline.JobContext;
import com.portway.app.pipeline.PipelineStage;
import com.portway.app.pipeline.Stage;
import com.portway.app.repo.SourceFileRepository;
import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceFile;
import com.portway.core.ir.SourceProject;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ClassifyStage implements PipelineStage {

  private final SourceFileRepository sources;

  public ClassifyStage(SourceFileRepository sources) {
    this.sources = sources;
  }

  @Override
  public Stage stage() {
    return Stage.CLASSIFY;
  }

  @Override
  @Transactional
  public String execute(JobContext context) {
    SourceProject project = context.session().classify();
    Map<String, String> roles =
        project.files().stream()
            .filter(f -> !f.types().isEmpty())
            .collect(Collectors.toMap(SourceFile::path, f -> f.types().get(0).role().name(), (a, b) -> a));
    for (SourceFileEntity source : sources.findByJobIdOrderByPathAsc(context.jobId())) {
      String role = roles.get(source.getPath());
      if (role != null) {
        source.setDetectedRole(role);
      } else if (source.getPath().endsWith("Program.cs") || source.getPath().endsWith("Startup.cs")) {
        source.setDetectedRole(ClassRole.PROGRAM_ENTRY.name());
      }
    }

    // Enums carry no role by design: they are generated because entities use them.
    // Counting them as "unknown" would read like a classification failure.
    Map<String, Long> counts = new java.util.LinkedHashMap<>();
    project.allTypes().forEach(t -> counts.merge(
        t.kind() == com.portway.core.ir.TypeKind.ENUM ? "ENUM" : t.role().name(), 1L, Long::sum));
    context.stats().put("roles", counts);
    return counts.entrySet().stream()
        .map(e -> e.getValue() + " " + e.getKey().toLowerCase(Locale.ROOT).replace('_', ' '))
        .collect(Collectors.joining(", "));
  }
}
