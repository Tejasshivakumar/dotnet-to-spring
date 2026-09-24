using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace Reports;

public class ReportService : IReportService
{
    private readonly ILogger<ReportService> _logger;
    private readonly ReportingOptions _options;
    private int _rendered;

    public ReportService(ILogger<ReportService> logger, IOptions<ReportingOptions> options)
    {
        _logger = logger;
        _options = options.Value;
    }

    public async Task WarmUpAsync()
    {
        _logger.LogInformation("Warming up {Title}", _options.Title);
        await Task.Delay(10);
    }

    public async Task<string> RenderAsync(int rows)
    {
        if (rows > _options.MaxRows)
        {
            _logger.LogWarning("Clamping {Rows} to {Max}", rows, _options.MaxRows);
            rows = _options.MaxRows;
        }

        _rendered++;
        return $"{_options.Title}: {rows} rows";
    }

    public ValueTask<int> CountAsync() => ValueTask.FromResult(_rendered);
}
