package com.portway.core.classify;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import java.util.Optional;

/** {@code [ApiController]}, or the naming and base-class convention that predates it. */
public class ControllerClassifier implements Classifier {

  @Override
  public Optional<ClassRole> classify(TypeDecl type, SourceProject project) {
    if (type.hasAttribute("ApiController")) {
      return Optional.of(ClassRole.CONTROLLER);
    }
    if (type.name().endsWith("Controller")
        && (type.hasBaseType("ControllerBase") || type.hasBaseType("Controller"))) {
      return Optional.of(ClassRole.CONTROLLER);
    }
    return Optional.empty();
  }
}
