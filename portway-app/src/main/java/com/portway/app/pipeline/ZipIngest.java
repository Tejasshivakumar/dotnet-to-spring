package com.portway.app.pipeline;

import com.portway.app.config.PortwayProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/**
 * Extracts an uploaded project zip, treating it as hostile.
 *
 * <ul>
 *   <li><b>Zip slip.</b> An entry named {@code ../../etc/cron.d/x} must not land outside the job
 *       directory. Every entry's normalised target is checked to be inside it before anything is
 *       written.
 *   <li><b>Zip bombs.</b> A few kilobytes can inflate to gigabytes. The entry count, each entry's
 *       size and the total are capped, and sizes are counted while inflating: a zip's declared
 *       sizes are attacker-controlled and cannot be trusted.
 *   <li><b>Everything else.</b> Only the files Portway reads are extracted: .cs, .csproj and
 *       appsettings*.json. Build output under bin/ and obj/ is skipped.
 * </ul>
 */
@Component
public class ZipIngest {

  private static final Set<String> SKIPPED_DIRECTORIES = Set.of("bin", "obj", ".git", ".vs", "node_modules");

  private final PortwayProperties.Upload limits;

  public ZipIngest(PortwayProperties properties) {
    this.limits = properties.upload();
  }

  /** Extracted file counts, for the job's first stage. */
  public record Extracted(Path root, int files) {}

  /**
   * Extracts into {@code target} and returns the project root inside it: the directory holding the
   * .csproj, since zips usually wrap the project in a folder of their own.
   */
  public Extracted extract(InputStream upload, Path target) {
    // ZipInputStream reads a non-zip as a zip with no entries, which would then be
    // reported as "no C# sources". Check the local-file-header signature first.
    java.io.BufferedInputStream zip = new java.io.BufferedInputStream(upload);
    try {
      zip.mark(4);
      byte[] magic = zip.readNBytes(4);
      zip.reset();
      if (magic.length < 4 || magic[0] != 'P' || magic[1] != 'K' || magic[2] != 3 || magic[3] != 4) {
        throw new UploadRejectedException("Not a zip archive");
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Path base = target.toAbsolutePath().normalize();
    int entries = 0;
    int written = 0;
    long total = 0;
    try (ZipInputStream in = new ZipInputStream(zip)) {
      Files.createDirectories(base);
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (++entries > limits.maxEntries()) {
          throw new UploadRejectedException("Archive has more than " + limits.maxEntries() + " entries");
        }
        String name = entry.getName().replace('\\', '/');
        Path destination = base.resolve(name).normalize();
        if (!destination.startsWith(base) || name.startsWith("/") || name.contains(":")) {
          throw new UploadRejectedException("Archive entry escapes the upload directory: " + entry.getName());
        }
        if (entry.isDirectory() || !wanted(name)) {
          continue;
        }
        Files.createDirectories(destination.getParent());
        long size = copyBounded(in, destination, limits.maxEntryBytes(), name);
        total += size;
        if (total > limits.maxTotalBytes()) {
          throw new UploadRejectedException(
              "Archive inflates to more than " + limits.maxTotalBytes() / (1024 * 1024) + " MB");
        }
        written++;
      }
    } catch (ZipException e) {
      throw new UploadRejectedException("Not a valid zip archive: " + e.getMessage());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (written == 0) {
      throw new UploadRejectedException("Archive contains no C# sources (.cs files)");
    }
    return new Extracted(projectRoot(base), written);
  }

  static boolean wanted(String name) {
    for (String segment : name.split("/")) {
      if (SKIPPED_DIRECTORIES.contains(segment.toLowerCase(Locale.ROOT))) {
        return false;
      }
    }
    String file = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return file.endsWith(".cs")
        || file.endsWith(".csproj")
        || (file.startsWith("appsettings") && file.endsWith(".json"));
  }

  private static long copyBounded(InputStream in, Path destination, long max, String name) throws IOException {
    long size = 0;
    byte[] buffer = new byte[8192];
    try (OutputStream out = Files.newOutputStream(destination)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        size += read;
        if (size > max) {
          throw new UploadRejectedException(
              name + " inflates to more than " + max / (1024 * 1024) + " MB");
        }
        out.write(buffer, 0, read);
      }
    }
    return size;
  }

  /** The shallowest directory containing a .csproj, or the extraction root when there is none. */
  static Path projectRoot(Path base) {
    try (Stream<Path> walk = Files.walk(base)) {
      return walk.filter(p -> p.toString().endsWith(".csproj"))
          .min((a, b) -> Integer.compare(a.getNameCount(), b.getNameCount()))
          .map(Path::getParent)
          .orElseGet(() -> singleChild(base));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A zip of just sources, wrapped in one folder: use the folder. */
  private static Path singleChild(Path base) {
    try (Stream<Path> children = Files.list(base)) {
      var list = children.toList();
      return list.size() == 1 && Files.isDirectory(list.get(0)) ? list.get(0) : base;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
