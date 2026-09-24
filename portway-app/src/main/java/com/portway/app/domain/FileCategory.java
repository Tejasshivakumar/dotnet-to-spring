package com.portway.app.domain;

/** How the review UI groups generated files. Derived from the generated package layout. */
public enum FileCategory {
  CONTROLLER,
  ENTITY,
  REPOSITORY,
  SERVICE,
  DTO,
  CONFIG,
  MODEL,
  BUILD;

  public static FileCategory of(String path) {
    if (!path.endsWith(".java")) {
      return BUILD;
    }
    String dir = path.substring(0, path.lastIndexOf('/'));
    String leaf = dir.substring(dir.lastIndexOf('/') + 1);
    return switch (leaf) {
      case "controller" -> CONTROLLER;
      case "domain" -> ENTITY;
      case "repository" -> REPOSITORY;
      case "service" -> SERVICE;
      case "dto" -> DTO;
      case "config" -> CONFIG;
      case "model" -> MODEL;
      default -> BUILD;
    };
  }
}
