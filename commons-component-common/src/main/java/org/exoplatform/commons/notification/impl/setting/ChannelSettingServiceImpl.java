package org.exoplatform.commons.notification.impl.setting;

import java.util.List;

import org.exoplatform.commons.api.notification.plugin.NotificationChannelPlugin;
import org.exoplatform.commons.api.notification.plugin.config.ChannelConfig;
import org.exoplatform.commons.api.notification.service.setting.ChannelSettingService;
import org.exoplatform.commons.notification.impl.AbstractService;

public class ChannelSettingServiceImpl extends AbstractService implements ChannelSettingService {
  NotificationChannelPlugin notifChannelConfig;
  
  @Override
  public void registerNotifChannelPlugin(NotificationChannelPlugin notifChannelConfig) {
    this.notifChannelConfig = notifChannelConfig;
  }

  @Override
  public List<ChannelConfig> getAll() {
    return this.notifChannelConfig.getChanels();
  }
  
  @Override
  public ChannelConfig get(String id) {
    for (ChannelConfig channel : this.notifChannelConfig.getChanels()) {
      if (id.equals(channel.getId())) return channel;      
    }
    
    return null;
  }

  @Override
  public void save(String id) {
    
  }
}
