using Shop.Dtos;

namespace Shop.Services;

/// <summary>
/// LINQ over an in-memory list: each method is one chain shape.
/// </summary>
public class OrderQueryService
{
    private readonly List<OrderDto> _orders = new();

    public List<OrderDto> Pending()
    {
        return _orders.Where(o => !o.Shipped).OrderBy(o => o.PlacedOn).ToList();
    }

    public List<string> TopCustomers(int count)
    {
        return _orders
            .OrderByDescending(o => o.Quantity)
            .Select(o => o.Customer)
            .Distinct()
            .Take(count)
            .ToList();
    }

    public OrderDto? Latest() => _orders.OrderByDescending(o => o.PlacedOn).FirstOrDefault();

    public bool AnyLarge(int threshold) => _orders.Any(o => o.Quantity > threshold);

    public bool AllShipped() => _orders.All(o => o.Shipped);

    public long ShippedCount() => _orders.Count(o => o.Shipped);

    public int TotalQuantity() => _orders.Sum(o => o.Quantity);

    public IEnumerable<OrderDto> Page(int page, int size) =>
        _orders.Skip((page - 1) * size).Take(size);

    public int Size() => _orders.Count;

    public Dictionary<string, int> QuantityByCustomer()
    {
        return _orders
            .GroupBy(o => o.Customer)
            .ToDictionary(g => g.Key, g => g.Sum(o => o.Quantity));
    }
}
