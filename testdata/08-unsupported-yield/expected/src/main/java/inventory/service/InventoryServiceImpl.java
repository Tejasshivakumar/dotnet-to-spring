package inventory.service;

import inventory.dto.ItemDto;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class InventoryServiceImpl implements InventoryService {
  /**
   * MIGRATION: could not be translated automatically. Reason: uses yield return, which has no
   * direct Java equivalent.
   *
   * <p>Original C#:
   *
   * <pre>
   * public IEnumerable&lt;ItemDto&gt; InStock(List&lt;ItemDto&gt; items)
   * {
   *     foreach (var item in items)
   *     {
   *         if (item.OnHand &gt; 0)
   *         {
   *             yield return item;
   *         }
   *     }
   * }
   * </pre>
   */
  @Override
  public List<ItemDto> inStock(List<ItemDto> items) {
    throw new UnsupportedOperationException("TODO: migrate from C#");
  }

  /**
   * MIGRATION: could not be translated automatically. Reason: takes a ref, out or in parameter;
   * Java passes everything by value.
   *
   * <p>Original C#:
   *
   * <pre>
   * public bool TryReserve(string sku, out int remaining)
   * {
   *     remaining = 0;
   *     return false;
   * }
   * </pre>
   */
  @Override
  public boolean tryReserve(String sku, int remaining) {
    throw new UnsupportedOperationException("TODO: migrate from C#");
  }

  @Override
  public int total(int... counts) {
    var total = 0;
    for (var c : counts) {
      total += c;
    }

    return total;
  }

  /**
   * MIGRATION: could not be translated automatically. Reason: uses a switch expression.
   *
   * <p>Original C#:
   *
   * <pre>
   * public string Describe(ItemDto item) =&gt; item switch
   *     {
   *         { OnHand: 0 } =&gt; "out of stock",
   *         _ =&gt; $"{item.OnHand} left"
   *     };
   * </pre>
   */
  @Override
  public String describe(ItemDto item) {
    throw new UnsupportedOperationException("TODO: migrate from C#");
  }
}
