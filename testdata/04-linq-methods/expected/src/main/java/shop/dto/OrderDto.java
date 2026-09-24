package shop.dto;

import java.time.LocalDateTime;

public class OrderDto {
  private int id;

  private String customer = "";

  private int quantity;

  private boolean shipped;

  private LocalDateTime placedOn;

  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  public String getCustomer() {
    return customer;
  }

  public void setCustomer(String customer) {
    this.customer = customer;
  }

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }

  public boolean isShipped() {
    return shipped;
  }

  public void setShipped(boolean shipped) {
    this.shipped = shipped;
  }

  public LocalDateTime getPlacedOn() {
    return placedOn;
  }

  public void setPlacedOn(LocalDateTime placedOn) {
    this.placedOn = placedOn;
  }
}
