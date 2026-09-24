package com.portway.app.pipeline.stages;

import com.portway.app.pipeline.JobContext;
import com.portway.app.pipeline.PipelineStage;
import com.portway.app.pipeline.Stage;
import com.portway.core.translate.BodyTranslator;
import com.portway.core.verify.CompileVerifier;
import com.portway.core.verify.VerifiedMigration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sends the methods the rules could not handle to the LLM, when AI is enabled for the job and a
 * translator is configured. Tier C methods never reach it.
 */
@Component
public class AiFillStage implements PipelineStage {

  private final CompileVerifier verifier;
  private final ObjectProvider<BodyTranslator> translator;

  public AiFillStage(CompileVerifier verifier, ObjectProvider<BodyTranslator> translator) {
    this.verifier = verifier;
    this.translator = translator;
  }

  @Override
  public Stage stage() {
    return Stage.AI_FILL;
  }

  @Override
  public String execute(JobContext context) {
    BodyTranslator ai = context.aiEnabled() ? translator.getIfAvailable() : null;
    VerifiedMigration.Run run = new VerifiedMigration(verifier, ai).start(context.session());
    context.run(run);
    if (ai == null) {
      return context.aiEnabled() ? "AI requested but no model is configured; skipped" : "AI not enabled for this job";
    }
    long eligible = context.session().result().methods().stream().filter(m -> m.aiEligible()).count();
    int filled = run.aiFill(VerifiedMigration.Listener.NONE);
    return filled + " of " + eligible + " eligible methods translated";
  }
}
