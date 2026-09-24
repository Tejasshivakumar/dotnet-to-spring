var builder = WebApplication.CreateBuilder(args);
builder.Services.AddScoped<IInventoryService, InventoryService>();
var app = builder.Build();
app.Run();
