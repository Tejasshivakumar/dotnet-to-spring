namespace Inventory;

public class InventoryService : IInventoryService
{
    public IEnumerable<ItemDto> InStock(List<ItemDto> items)
    {
        foreach (var item in items)
        {
            if (item.OnHand > 0)
            {
                yield return item;
            }
        }
    }

    public bool TryReserve(string sku, out int remaining)
    {
        remaining = 0;
        return false;
    }

    public int Total(params int[] counts)
    {
        var total = 0;
        foreach (var c in counts)
        {
            total += c;
        }

        return total;
    }

    public string Describe(ItemDto item) => item switch
    {
        { OnHand: 0 } => "out of stock",
        _ => $"{item.OnHand} left"
    };
}
