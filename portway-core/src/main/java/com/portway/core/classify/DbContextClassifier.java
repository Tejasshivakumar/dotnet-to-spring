package com.portway.core.classify;

import com.portway.core.ir.ClassRole;
import com.portway.core.ir.SourceProject;
import com.portway.core.ir.TypeDecl;
import java.util.Optional;

/** Anything deriving from EF Core's DbContext. */
public class DbContextClassifier implements Classifier {

  @Override
  public Optional<ClassRole> classify(TypeDecl type, SourceProject project) {
    return type.hasBaseType("DbContext") ? Optional.of(ClassRole.DB_CONTEXT) : Optional.empty();
  }
}
