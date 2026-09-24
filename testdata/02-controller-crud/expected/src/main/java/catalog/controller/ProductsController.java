package catalog.controller;

import catalog.dto.ProductDto;
import catalog.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Product catalogue endpoints. */
@RestController
@RequestMapping("/api/products")
public class ProductsController {
  private final ProductService products;

  public ProductsController(ProductService products) {
    this.products = products;
  }

  @GetMapping
  public ResponseEntity<?> search(
      @RequestParam(required = false) String name,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "size", defaultValue = "20") int pageSize) {
    if (page < 1) {
      return ResponseEntity.badRequest().body("page must be at least 1");
    }

    return ResponseEntity.ok(products.search(name, page, pageSize));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ProductDto> get(@PathVariable int id) {
    var product = products.find(id);
    return product == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(product);
  }

  @PostMapping
  public ResponseEntity<ProductDto> create(@Valid @RequestBody ProductDto product) {
    var created = products.create(product);
    return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/products/{id}")
                .buildAndExpand(created.getId())
                .toUri())
        .body(created);
  }

  @PutMapping("/{id}")
  public ResponseEntity<?> update(@PathVariable int id, @Valid @RequestBody ProductDto product) {
    if (id != product.getId()) {
      return ResponseEntity.badRequest().build();
    }

    var updated = products.update(id, product);
    return updated ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('Admin')")
  public ResponseEntity<?> delete(@PathVariable int id) {
    return products.delete(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}
