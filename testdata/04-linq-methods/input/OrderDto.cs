namespace Shop.Dtos;

public class OrderDto
{
    public int Id { get; set; }

    public string Customer { get; set; } = string.Empty;

    public int Quantity { get; set; }

    public bool Shipped { get; set; }

    public DateTime PlacedOn { get; set; }
}
