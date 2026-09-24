package com.portway.app.pipeline.stages;

import com.portway.app.pipeline.JobContext;
import com.portway.app.pipeline.PipelineStage;
import com.portway.app.pipeline.Stage;
import com.portway.app.ai.AiTranslatorFactory;
import com.portway.app.ai.LlmBodyTranslator;
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
  private final ObjectProvider<AiTranslatorFactory> translator;

  public AiFillStage(CompileVerifier verifier, ObjectProvider<AiTranslatorFactory> translator) {
    this.verifier = verifier;
    this.translator = translator;
  }

  @Override
  public Stage stage() {
    return Stage.AI_FILL;
  }

  @Override
  public String execute(JobContext context) {
    AiTranslatorFactory factory = context.aiEnabled() ? translator.getIfAvailable() : null;
    // One translator per job: its token budget and usage figures are this job's alone.
    LlmBodyTranslator ai = factory == null ? null : factory.create();
    VerifiedMigration.Run run = new VerifiedMigration(verifier, ai).start(context.session());
    context.run(run);
    context.translator(ai);
    if (ai == null) {
      return context.aiEnabled() ? "AI requested but no model is configured; skipped" : "AI not enabled for this job";
    }
    long eligible = context.session().result().methods().stream().filter(m -> m.aiEligible()).count();
    int filled = run.aiFill(VerifiedMigration.Listener.NONE);
    return filled + " of " + eligible + " eligible methods translated (" + ai.usage().totalTokens() + " tokens)";
  }
}
