/*
 * Cardano DB-Sync API
 * No description provided (generated by Openapi Generator https://github.com/openapitools/openapi-generator)
 *
 * The version of the OpenAPI document: 1.0
 * 
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */


package de.peterspace.cardanodbsyncapi.client.model;

import java.util.Objects;
import java.util.Arrays;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * OwnerInfo
 */
@JsonPropertyOrder({
  OwnerInfo.JSON_PROPERTY_ADDRESS,
  OwnerInfo.JSON_PROPERTY_AMOUNT,
  OwnerInfo.JSON_PROPERTY_MA_NAMES
})
@jakarta.annotation.Generated(value = "org.openapitools.codegen.languages.JavaClientCodegen", date = "2024-01-08T17:32:52.207422400+01:00[Europe/Berlin]")
public class OwnerInfo {
  public static final String JSON_PROPERTY_ADDRESS = "address";
  private String address;

  public static final String JSON_PROPERTY_AMOUNT = "amount";
  private Long amount;

  public static final String JSON_PROPERTY_MA_NAMES = "maNames";
  private List<String> maNames = new ArrayList<>();

  public OwnerInfo() {
  }

  public OwnerInfo address(String address) {
    
    this.address = address;
    return this;
  }

   /**
   * Get address
   * @return address
  **/
  @jakarta.annotation.Nonnull
  @JsonProperty(JSON_PROPERTY_ADDRESS)
  @JsonInclude(value = JsonInclude.Include.ALWAYS)

  public String getAddress() {
    return address;
  }


  @JsonProperty(JSON_PROPERTY_ADDRESS)
  @JsonInclude(value = JsonInclude.Include.ALWAYS)
  public void setAddress(String address) {
    this.address = address;
  }


  public OwnerInfo amount(Long amount) {
    
    this.amount = amount;
    return this;
  }

   /**
   * Get amount
   * @return amount
  **/
  @jakarta.annotation.Nonnull
  @JsonProperty(JSON_PROPERTY_AMOUNT)
  @JsonInclude(value = JsonInclude.Include.ALWAYS)

  public Long getAmount() {
    return amount;
  }


  @JsonProperty(JSON_PROPERTY_AMOUNT)
  @JsonInclude(value = JsonInclude.Include.ALWAYS)
  public void setAmount(Long amount) {
    this.amount = amount;
  }


  public OwnerInfo maNames(List<String> maNames) {
    
    this.maNames = maNames;
    return this;
  }

  public OwnerInfo addMaNamesItem(String maNamesItem) {
    if (this.maNames == null) {
      this.maNames = new ArrayList<>();
    }
    this.maNames.add(maNamesItem);
    return this;
  }

   /**
   * Get maNames
   * @return maNames
  **/
  @jakarta.annotation.Nonnull
  @JsonProperty(JSON_PROPERTY_MA_NAMES)
  @JsonInclude(value = JsonInclude.Include.ALWAYS)

  public List<String> getMaNames() {
    return maNames;
  }


  @JsonProperty(JSON_PROPERTY_MA_NAMES)
  @JsonInclude(value = JsonInclude.Include.ALWAYS)
  public void setMaNames(List<String> maNames) {
    this.maNames = maNames;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    OwnerInfo ownerInfo = (OwnerInfo) o;
    return Objects.equals(this.address, ownerInfo.address) &&
        Objects.equals(this.amount, ownerInfo.amount) &&
        Objects.equals(this.maNames, ownerInfo.maNames);
  }

  @Override
  public int hashCode() {
    return Objects.hash(address, amount, maNames);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class OwnerInfo {\n");
    sb.append("    address: ").append(toIndentedString(address)).append("\n");
    sb.append("    amount: ").append(toIndentedString(amount)).append("\n");
    sb.append("    maNames: ").append(toIndentedString(maNames)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }

}

