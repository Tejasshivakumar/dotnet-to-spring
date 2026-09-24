package reports.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reports.config.ReportingOptions;

@Service
public class ReportServiceImpl implements ReportService {
  private static final Logger log = LoggerFactory.getLogger(ReportServiceImpl.class);

  private final ReportingOptions options;

  private int rendered;

  public ReportServiceImpl(ReportingOptions options) {
    this.options = options;
  }

  /**
   * MIGRATION: could not be translated automatically. Reason: uses the Task API directly (delays,
   * WhenAll, Run), which has no synchronous equivalent a rule can choose.
   *
   * <p>Original C#:
   *
   * <pre>
   * public async Task WarmUpAsync()
   * {
   *     _logger.LogInformation("Warming up {Title}", _options.Title);
   *     await Task.Delay(10);
   * }
   * </pre>
   */
  @Override
  public void warmUp() {
    throw new UnsupportedOperationException("TODO: migrate from C#");
  }

  @Override
  public String render(int rows) {
    if (rows > options.getMaxRows()) {
      log.warn("Clamping {} to {}", rows, options.getMaxRows());
      rows = options.getMaxRows();
    }

    rendered++;
    return options.getTitle() + ": " + rows + " rows";
  }

  @Override
  public int count() {
    return rendered;
  }
}
