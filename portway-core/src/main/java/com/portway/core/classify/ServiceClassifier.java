package com.portway.core.classify;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import java.util.Optional;

/** A class registered as the implementation of a DI registration, or named like a service. */
public class ServiceClassifier implements Classifier {

  @Override
  public Optional<ClassRole> classify(TypeDecl type, SourceProject project) {
    if (type.kind() != TypeKind.CLASS) {
      return Optional.empty();
    }
    if (ProgramScanner.registrations(project).implementations().contains(type.name())) {
      return Optional.of(ClassRole.SERVICE);
    }
    return type.name().endsWith("Service") ? Optional.of(ClassRole.SERVICE) : Optional.empty();
  }
}
