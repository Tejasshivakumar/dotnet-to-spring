package com.portway.app.pipeline.stages;

import com.portway.app.pipeline.JobContext;
import com.portway.app.pipeline.PipelineStage;
import com.portway.app.pipeline.Stage;
import com.portway.core.verify.CompileVerifier;
import com.portway.core.verify.VerifiedMigration;
import org.springframework.stereotype.Component;

/** Compiles the generated project and runs the repair loop, reporting REPAIR while it repairs. */
@Component
public class VerifyStage implements PipelineStage {

  private final CompileVerifier verifier;

  public VerifyStage(CompileVerifier verifier) {
    this.verifier = verifier;
  }

  @Override
  public Stage stage() {
    return Stage.VERIFY;
  }

  @Override
  public String execute(JobContext context) {
    VerifiedMigration.Outcome outcome =
        context.run().verify(
            context.workDir().resolve("verify"),
            new VerifiedMigration.Listener() {
              @Override
              public void verify(int round) {
                if (round > 1) {
                  context.reportStage(Stage.VERIFY);
                }
              }

              @Override
              public void repair(int round, int methods) {
                context.reportStage(Stage.REPAIR);
              }
            });
    context.outcome(outcome);
    String status = outcome.compiles() ? "PASS" : "FAIL, " + outcome.compile().errors().size() + " errors";
    return status + " after " + outcome.rounds() + " round" + (outcome.rounds() == 1 ? "" : "s")
        + " (" + verifier.name() + ")";
  }
}
