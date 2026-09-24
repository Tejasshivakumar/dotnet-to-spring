package accounts.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Every validation attribute the attribute mapper knows, on one request. */
public class RegistrationRequest {
  @NotNull
  @Size(min = 3, max = 50)
  private String username = "";

  @NotNull
  @Email
  @JsonProperty("email_address")
  private String email = "";

  @Size(min = 12, max = 128)
  @JsonIgnore
  private String password = "";

  @Min(13)
  @Max(120)
  private int age;

  @Pattern(regexp = "^[A-Z]{2}$")
  private String countryCode;

  @Deprecated private String legacyId;

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public int getAge() {
    return age;
  }

  public void setAge(int age) {
    this.age = age;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(String countryCode) {
    this.countryCode = countryCode;
  }

  public String getLegacyId() {
    return legacyId;
  }

  public void setLegacyId(String legacyId) {
    this.legacyId = legacyId;
  }
}
