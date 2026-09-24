namespace Reports;

public interface IReportService
{
    Task WarmUpAsync();

    Task<string> RenderAsync(int rows);

    ValueTask<int> CountAsync();
}
