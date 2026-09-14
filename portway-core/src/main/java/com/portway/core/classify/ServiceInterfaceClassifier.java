package com.portway.core.classify;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import com.portway.core.ir.TypeKind;
import java.util.Optional;

/**
 * An interface registered as the service type of a DI registration, or failing that, one named like
 * a service.
 *
 * <p>The registration is the stronger signal, but Program.cs uses top-level statements that the
 * grammar predates, so registrations are recovered by {@link ProgramScanner} instead.
 */
public class ServiceInterfaceClassifier implements Classifier {

  @Override
  public Optional<ClassRole> classify(TypeDecl type, SourceProject project) {
    if (type.kind() != TypeKind.INTERFACE) {
      return Optional.empty();
    }
    if (ProgramScanner.registrations(project).serviceInterfaces().contains(type.name())) {
      return Optional.of(ClassRole.SERVICE_INTERFACE);
    }
    return type.name().endsWith("Service") || type.name().endsWith("Repository")
        ? Optional.of(ClassRole.SERVICE_INTERFACE)
        : Optional.empty();
  }
}
