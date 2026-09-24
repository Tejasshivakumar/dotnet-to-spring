package shop.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import shop.dto.OrderDto;

/** LINQ over an in-memory list: each method is one chain shape. */
@Service
public class OrderQueryService {
  private final List<OrderDto> orders = new ArrayList<>();

  public List<OrderDto> pending() {
    return orders.stream()
        .filter(o -> !o.isShipped())
        .sorted(Comparator.comparing(o -> o.getPlacedOn()))
        .toList();
  }

  public List<String> topCustomers(int count) {
    return orders.stream()
        .sorted(Comparator.comparing(o -> o.getQuantity(), Comparator.reverseOrder()))
        .map(o -> o.getCustomer())
        .distinct()
        .limit(count)
        .toList();
  }

  public OrderDto latest() {
    return orders.stream()
        .sorted(Comparator.comparing(o -> o.getPlacedOn(), Comparator.reverseOrder()))
        .findFirst()
        .orElse(null);
  }

  public boolean anyLarge(int threshold) {
    return orders.stream().anyMatch(o -> o.getQuantity() > threshold);
  }

  public boolean allShipped() {
    return orders.stream().allMatch(o -> o.isShipped());
  }

  public long shippedCount() {
    return orders.stream().filter(o -> o.isShipped()).count();
  }

  public int totalQuantity() {
    return orders.stream().mapToInt(o -> o.getQuantity()).sum();
  }

  public List<OrderDto> page(int page, int size) {
    return orders.stream().skip((page - 1) * size).limit(size).toList();
  }

  public int size() {
    return orders.size();
  }

  /**
   * MIGRATION: could not be translated automatically. Reason: uses a LINQ operator with no
   * single-step stream equivalent.
   *
   * <p>Original C#:
   *
   * <pre>
   * public Dictionary&lt;string,int&gt; QuantityByCustomer()
   * {
   *     return _orders
   *         .GroupBy(o =&gt; o.Customer)
   *         .ToDictionary(g =&gt; g.Key, g =&gt; g.Sum(o =&gt; o.Quantity));
   * }
   * </pre>
   */
  public Map<String, Integer> quantityByCustomer() {
    throw new UnsupportedOperationException("TODO: migrate from C#");
  }
}
