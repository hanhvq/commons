/**
 * 
 */
package org.exoplatform.commons.api.notification.plugin;

import java.util.ArrayList;
import java.util.List;

import org.exoplatform.commons.api.notification.plugin.config.ChannelConfig;
import org.exoplatform.container.component.BaseComponentPlugin;
import org.exoplatform.container.xml.InitParams;

public class NotificationChannelPlugin extends BaseComponentPlugin {
  private List<ChannelConfig> chanels = new ArrayList<ChannelConfig>();
  
  public NotificationChannelPlugin(InitParams initParams) {
    chanels = initParams.getObjectParamValues(ChannelConfig.class);
  }

  public List<ChannelConfig> getChanels() {
    return chanels;
  }
}
