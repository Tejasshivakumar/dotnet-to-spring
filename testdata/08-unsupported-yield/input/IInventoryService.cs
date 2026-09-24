namespace Inventory;

public interface IInventoryService
{
    IEnumerable<ItemDto> InStock(List<ItemDto> items);

    bool TryReserve(string sku, out int remaining);

    int Total(params int[] counts);

    string Describe(ItemDto item);
}
