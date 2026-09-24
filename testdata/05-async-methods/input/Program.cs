using Reports;

var builder = WebApplication.CreateBuilder(args);
builder.Services.Configure<ReportingOptions>(builder.Configuration.GetSection("Reporting"));
builder.Services.AddScoped<IReportService, ReportService>();
var app = builder.Build();
app.Run();
