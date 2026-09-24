package com.portway.app.pipeline.stages;

import com.portway.app.pipeline.JobContext;
import com.portway.app.pipeline.PipelineStage;
import com.portway.app.pipeline.Stage;
import com.portway.core.MigrationSession;
import com.portway.core.ir.SourceProject;
import org.springframework.stereotype.Component;

@Component
public class ParseStage implements PipelineStage {

  @Override
  public Stage stage() {
    return Stage.PARSE;
  }

  @Override
  public String execute(JobContext context) {
    MigrationSession session = new MigrationSession(context.sourceDir(), context.options());
    SourceProject project = session.parse();
    context.session(session);
    long rejected = session.syntaxErrors().stream().map(e -> e.path()).distinct().count();
    long types = project.allTypes().count();
    context.stats().put("parsedFiles", project.files().size());
    context.stats().put("rejectedFiles", rejected);
    return project.files().size() + " files parsed into " + types + " types"
        + (rejected == 0 ? "" : ", " + rejected + " rejected");
  }
}
