package com.portway.core.ir;

/** A NuGet PackageReference from the .csproj. */
public record PackageRef(String id, String version) {}
