package inventory.dto;

public class ItemDto {
  private String sku = "";

  private int onHand;

  public String getSku() {
    return sku;
  }

  public void setSku(String sku) {
    this.sku = sku;
  }

  public int getOnHand() {
    return onHand;
  }

  public void setOnHand(int onHand) {
    this.onHand = onHand;
  }
}
