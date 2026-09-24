package catalog.service;

import catalog.dto.ProductDto;
import java.util.List;

public interface ProductService {
  List<ProductDto> search(String name, int page, int pageSize);

  ProductDto find(int id);

  ProductDto create(ProductDto product);

  boolean update(int id, ProductDto product);

  boolean delete(int id);
}
