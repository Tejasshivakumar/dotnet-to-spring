package com.portway.core.classify;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import java.util.Optional;

/** Options and settings classes, which become {@code @ConfigurationProperties} beans. */
public class ConfigurationClassifier implements Classifier {

  @Override
  public Optional<ClassRole> classify(TypeDecl type, SourceProject project) {
    return type.name().endsWith("Options") || type.name().endsWith("Settings")
        ? Optional.of(ClassRole.CONFIGURATION)
        : Optional.empty();
  }
}
