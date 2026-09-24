package com.portway.app.pipeline.stages;

import com.portway.app.pipeline.JobContext;
import com.portway.app.pipeline.PipelineStage;
import com.portway.app.pipeline.Stage;
import com.portway.core.MigrationResult;
import com.portway.core.report.MethodReport;
import com.portway.core.rules.body.Tier;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GenerateStage implements PipelineStage {

  @Override
  public Stage stage() {
    return Stage.GENERATE;
  }

  @Override
  public String execute(JobContext context) {
    MigrationResult result = context.session().generate();
    Map<Tier, Integer> tiers = new EnumMap<>(Tier.class);
    for (MethodReport m : result.methods()) {
      if (m.tier() != null) {
        tiers.merge(m.tier(), 1, Integer::sum);
      }
    }
    context.stats().put("tiers", Map.of(
        "A", tiers.getOrDefault(Tier.A, 0),
        "B", tiers.getOrDefault(Tier.B, 0),
        "C", tiers.getOrDefault(Tier.C, 0)));
    return result.files().size() + " files; " + tiers.getOrDefault(Tier.A, 0) + " methods by rules, "
        + tiers.getOrDefault(Tier.B, 0) + " eligible for AI, " + tiers.getOrDefault(Tier.C, 0) + " manual";
  }
}
