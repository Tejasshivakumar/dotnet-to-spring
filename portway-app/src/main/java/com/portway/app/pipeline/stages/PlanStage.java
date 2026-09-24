package com.portway.app.pipeline.stages;

import com.portway.app.pipeline.JobContext;
import com.portway.app.pipeline.PipelineStage;
import com.portway.app.pipeline.Stage;
import com.portway.core.ir.ClassRole;
import com.portway.core.ir.MethodDecl;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides what will be generated before generating it: which types produce files, and how many
 * method bodies the rules and, if needed, the model will have to handle.
 */
@Component
public class PlanStage implements PipelineStage {

  private static final Set<ClassRole> WITH_BODIES = Set.of(ClassRole.CONTROLLER, ClassRole.SERVICE, ClassRole.REPOSITORY);

  @Override
  public Stage stage() {
    return Stage.PLAN;
  }

  @Override
  public String execute(JobContext context) {
    SourceProject project = context.session().project();
    long types = project.allTypes().filter(t -> t.role() != ClassRole.PROGRAM_ENTRY).count();
    long methods =
        project.allTypes()
            .filter(t -> WITH_BODIES.contains(t.role()))
            .mapToLong(t -> t.methods().stream().filter(MethodDecl::hasBody).count())
            .sum();
    long entities = project.typesWithRole(ClassRole.ENTITY).count();
    context.stats().put("plannedMethods", methods);
    return types + " types to generate (" + entities + " entities), " + methods + " method bodies to translate";
  }

  static long count(SourceProject project, ClassRole role) {
    return project.allTypes().map(TypeDecl::role).filter(role::equals).count();
  }
}
