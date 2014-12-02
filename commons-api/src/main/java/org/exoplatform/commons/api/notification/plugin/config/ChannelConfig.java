package org.exoplatform.commons.api.notification.plugin.config;

public class ChannelConfig {
  private String id;
  private String resourceBundleKey = "";  
  private String order;
  public String getId() {
    return id;
  }
  public void setId(String id) {
    this.id = id;
  }
  public String getResourceBundleKey() {
    return resourceBundleKey;
  }
  public void setResourceBundleKey(String resourceBundleKey) {
    this.resourceBundleKey = resourceBundleKey;
  }
  public String getOrder() {
    return order;
  }
  public void setOrder(String order) {
    this.order = order;
  }
}
