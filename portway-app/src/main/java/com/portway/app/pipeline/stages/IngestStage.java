package com.portway.app.pipeline.stages;

import com.portway.app.domain.SourceFileEntity;
import com.portway.app.pipeline.JobContext;
import com.portway.app.pipeline.PipelineStage;
import com.portway.app.pipeline.Stage;
import com.portway.app.repo.SourceFileRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Records the uploaded sources. The archive itself was extracted and vetted in the upload request,
 * so a hostile zip is refused with a 400 before any job runs.
 */
@Component
public class IngestStage implements PipelineStage {

  private final SourceFileRepository sources;

  public IngestStage(SourceFileRepository sources) {
    this.sources = sources;
  }

  @Override
  public Stage stage() {
    return Stage.INGEST;
  }

  @Override
  public String execute(JobContext context) {
    Path root = context.sourceDir();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(root)) {
      files = walk.filter(Files::isRegularFile).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    for (Path file : files) {
      String relative = root.relativize(file).toString().replace('\\', '/');
      sources.save(new SourceFileEntity(context.jobId(), relative, read(file)));
    }
    long cs = files.stream().filter(f -> f.toString().endsWith(".cs")).count();
    return cs + " C# files, " + (files.size() - cs) + " project and settings files";
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
