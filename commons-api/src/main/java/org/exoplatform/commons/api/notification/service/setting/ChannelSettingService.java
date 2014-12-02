package org.exoplatform.commons.api.notification.service.setting;

import java.util.List;

import org.exoplatform.commons.api.notification.plugin.NotificationChannelPlugin;
import org.exoplatform.commons.api.notification.plugin.config.ChannelConfig;

public interface ChannelSettingService {
  /**
   * 
   * @param notifChannelConfig
   */
  public void registerNotifChannelPlugin(NotificationChannelPlugin notifChannelConfig);
  
  public List<ChannelConfig> getAll();
  
  public ChannelConfig get(String id);
  
  public void save(String id);
}
