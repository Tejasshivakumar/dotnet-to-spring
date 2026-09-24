package inventory.service;

import inventory.dto.ItemDto;
import java.util.List;

public interface InventoryService {
  List<ItemDto> inStock(List<ItemDto> items);

  boolean tryReserve(String sku, int remaining);

  int total(int... counts);

  String describe(ItemDto item);
}
