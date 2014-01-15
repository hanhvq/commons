/*
 * Copyright (C) 2003-${year} eXo Platform SAS.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.commons.notification.impl.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;

import org.exoplatform.commons.api.notification.model.MessageInfo;
import org.exoplatform.commons.api.notification.service.QueueMessage;
import org.exoplatform.commons.notification.NotificationConfiguration;
import org.exoplatform.commons.notification.NotificationContextFactory;
import org.exoplatform.commons.notification.NotificationUtils;
import org.exoplatform.commons.notification.impl.AbstractService;
import org.exoplatform.commons.notification.job.SendEmailNotificationJob;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.management.annotations.ManagedBy;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.jcr.impl.core.query.QueryImpl;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.mail.MailService;
import org.exoplatform.services.mail.Message;
import org.exoplatform.services.scheduler.JobInfo;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.services.scheduler.PeriodInfo;
import org.json.JSONObject;
import org.picocontainer.Startable;
import org.quartz.JobDataMap;

@ManagedBy(SendEmailService.class)
public class QueueMessageImpl extends AbstractService implements QueueMessage, Startable {
  private static final Log               LOG                   = ExoLogger.getExoLogger(QueueMessageImpl.class);

  private static final String            MAX_TO_SEND_SYS_KEY   = "conf.notification.service.QueueMessage.numberOfMailPerBatch";
  private static final String            MAX_TO_SEND_KEY       = "numberOfMailPerBatch";
  private static final String            DELAY_TIME_SYS_KEY    = "conf.notification.service.QueueMessage.period";
  private static final String            DELAY_TIME_KEY        = "period";
  private static final String            CACHE_REPO_NAME       = "repositoryName";
  private static final int               BUFFER_SIZE           = 32;
  private static int                     LIMIT                 = 20;
  private static long                    sinceTime             = 0;

  private int                            MAX_TO_SEND;
  private long                           DELAY_TIME;
  /** .. */
  private SendEmailService               sendEmailService;
  /** .. */
  private MailService                    mailService;
  /** .. */
  private NotificationConfiguration      configuration;
  /** The lock protecting all mutators */
  transient final ReentrantLock lock = new ReentrantLock();
  /** .. */
  private static List<MessageInfo> messages = new CopyOnWriteArrayList<MessageInfo>();
  /** .. */
  private static ThreadLocal<Set<String>> idsRemovingLocal = new ThreadLocal<Set<String>>();
  
  public QueueMessageImpl(InitParams params) {
    this.configuration = CommonsUtils.getService(NotificationConfiguration.class);
    this.mailService = CommonsUtils.getService(MailService.class);

    MAX_TO_SEND = NotificationUtils.getSystemValue(params, MAX_TO_SEND_SYS_KEY, MAX_TO_SEND_KEY, 20);
    DELAY_TIME = NotificationUtils.getSystemValue(params, DELAY_TIME_SYS_KEY, DELAY_TIME_KEY, 120) * 1000;
  }
  
  public void setManagementView(SendEmailService managementView) {
    this.sendEmailService = managementView;
  }

  public void makeJob(int limit, long interval) {
    if (interval > 0) {
      LIMIT = limit;
      //
      JobSchedulerService schedulerService = CommonsUtils.getService(JobSchedulerService.class);
      Calendar cal = new GregorianCalendar();
      //
      try {
        PeriodInfo periodInfo = new PeriodInfo(cal.getTime(), null, -1, interval);
        JobInfo info = new JobInfo("SendEmailNotificationJob", "Notification", SendEmailNotificationJob.class);
        info.setDescription("Send email notification job.");
        //
        schedulerService.removeJob(info);

        JobDataMap jdatamap = new JobDataMap();
        jdatamap.put(CACHE_REPO_NAME, CommonsUtils.getRepository().getConfiguration().getName());
        //
        schedulerService.addPeriodJob(info, periodInfo, jdatamap);
      } catch (Exception e) {
        LOG.warn("Failed to add send email notification jobs ", e);
      }
    }
  }

  public void resetDefaultConfigJob() {
    makeJob(MAX_TO_SEND, DELAY_TIME);
  }

  @Override
  public void start() {
    //
    resetDefaultConfigJob();
    //
    sendEmailService.registerManager(this);
  }

  @Override
  public void stop() {
  }

  @Override
  public boolean put(MessageInfo message) {
    //
    if (message == null || message.getTo() == null || message.getTo().length() == 0) {
      return false;
    }
    //
    if (NotificationUtils.isValidEmailAddresses(message.getTo()) == false) {
      LOG.warn(String.format("The email %s is not valid for sending notification", message.getTo()));
      return false;
    }
    //
    saveMessageInfo(message);
    //
    sendEmailService.addCurrentCapacity();
    return true;
  }

  @Override
  public void send() {
    final boolean stats = NotificationContextFactory.getInstance().getStatistics().isStatisticsEnabled();
    SessionProvider sProvider = SessionProvider.createSystemProvider();
    
    try {
      //
      load(sProvider);
      
      idsRemovingLocal.set(new HashSet<String>());
      for (MessageInfo messageInfo : messages) {
        if (messageInfo != null && sendMessage(messageInfo.makeEmailNotification()) == true) {
          idsRemovingLocal.get().add(messageInfo.getId());
          if (stats) {
            NotificationContextFactory.getInstance().getStatisticsCollector().pollQueue(messageInfo.getPluginId());
          }
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to sending MessageInfos: ", e);
    } finally {
      sProvider.close();
      removeMessageInfo();
    }
  }

  /**
   * Loading the messageInfo as buffer with Limit
   * and sinceTime
   * @param sProvider
   */
  private void load(SessionProvider sProvider) {
    final ReentrantLock lock = this.lock;
    lock.lock();
    int index = 0;
    try {
      NodeIterator iterator = getMessageInfoNodes(sProvider);
      while (iterator.hasNext()) {
        Node node = iterator.nextNode();
        long createdTime = Long.parseLong(node.getName());
        if ((sinceTime == 0 || sinceTime < createdTime) && index < LIMIT) {
          MessageInfo messageInfo = getMessageInfo(node);
          messageInfo.setId(node.getUUID());
          messages.add(messageInfo);

          sinceTime = createdTime;
          index++;
        } else {
          sinceTime = 0;
          messages.clear();
          break;
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to sendding MessageInfos: ", e);
    } finally {
      lock.unlock();
    }
  }

  private void saveMessageInfo(MessageInfo message) {
    final ReentrantLock lock = this.lock;
    lock.lock();
    SessionProvider sProvider = SessionProvider.createSystemProvider();
    try {
      message.setCreatedTime(System.nanoTime());
      Node messageInfoHome = getMessageInfoHomeNode(sProvider, configuration.getWorkspace());
      Node messageInfoNode = messageInfoHome.addNode("" + message.getCreatedTime(), NTF_MESSAGE_INFO);
      if(messageInfoNode.canAddMixin("mix:referenceable")) {
        messageInfoNode.addMixin("mix:referenceable");
      }
      
      //
      saveData(messageInfoNode, compress(message.toJSON()));

      sessionSave(messageInfoHome);
    } catch (Exception e) {
      LOG.warn("Failed to storage MessageInfo: " + message.toJSON(), e);
    } finally {
      sProvider.close();
      lock.unlock();
    }
  }

  private void removeMessageInfo() {
    final ReentrantLock lock = this.lock;
    lock.lock();
    SessionProvider sProvider = SessionProvider.createSystemProvider();
    Session session = getSession(sProvider, configuration.getWorkspace());
    try {
      Set<String> ids = idsRemovingLocal.get();
      for (String messageId : ids) {
        session.getNodeByUUID(messageId).remove();
        //
        sendEmailService.removeCurrentCapacity();
        LOG.debug("remove MessageInfo " + messageId);
      }
      session.save();
    } catch (Exception e) {
      LOG.error("Failed to remove MessageInfo ", e);
    } finally {
      messages.clear();
      sProvider.close();
      lock.unlock();
    }
  }

  private NodeIterator getMessageInfoNodes(SessionProvider sProvider) {
    try {
      Node messageInfoHome = getMessageInfoHomeNode(sProvider, configuration.getWorkspace());
      QueryManager qm = messageInfoHome.getSession().getWorkspace().getQueryManager();
      StringBuffer stringBuffer = new StringBuffer();
      stringBuffer.append("SELECT * FROM ntf:messageInfo ")
      .append("ORDER BY exo:name");
      QueryImpl query = (QueryImpl) qm.createQuery(stringBuffer.toString(), Query.SQL);
      query.setOffset(0);
      query.setLimit(LIMIT);
      QueryResult result = query.execute();
      return result.getNodes();
    } catch (Exception e) {
      LOG.error("Failed to getMessageInfos", e);
    }
    return null;
  }

  private MessageInfo getMessageInfo(Node messageInfoNode) {
    try {
      String messageJson = getDataJson(messageInfoNode);
      JSONObject object = new JSONObject(messageJson);
      MessageInfo info = new MessageInfo();
      info.pluginId(object.optString("pluginId"))
          .from(object.getString("from"))
          .to(object.getString("to"))
          .subject(object.getString("subject"))
          .body(object.getString("body"))
          .footer(object.optString("footer"))
          .setCreatedTime(object.getLong("createdTime"));
      //
      return info;
    } catch (Exception e) {
      LOG.warn("Failed to set back MessageInfo: ", e);
    }
    return null;
  }

  private boolean sendMessage(Message message) {
    if (sendEmailService.isOn() == false) {
      try {
        //ensure the message is valid
        if (message.getFrom() == null) {
          return false;
        }
        mailService.sendMessage(message);
        return true;
      } catch (Exception e) {
        LOG.error("Failed to send notification.", e);
        return false;
      }
    } else {
      sendEmailService.counter();
    }
    // if service is off, removed message.
    return true;
  }

  private void saveData(Node node, InputStream is) throws Exception {
    Node fileNode = node.addNode("datajson", "nt:file");
    Node nodeContent = fileNode.addNode("jcr:content", "nt:resource");
    //
    nodeContent.setProperty("jcr:mimeType", "application/x-gzip");
    nodeContent.setProperty("jcr:data", is);
    nodeContent.setProperty("jcr:lastModified", Calendar.getInstance().getTimeInMillis());
  }

  private String getDataJson(Node node) throws Exception {
    Node fileNode = node.getNode("datajson");
    Node nodeContent = fileNode.getNode("jcr:content");
    InputStream stream = nodeContent.getProperty("jcr:data").getStream();
    return decompress(stream);
  }

  public static InputStream compress(String string) throws IOException {
    ByteArrayOutputStream os = new ByteArrayOutputStream(string.length());
    GZIPOutputStream gos = new GZIPOutputStream(os);
    gos.write(string.getBytes());
    gos.close();
    byte[] compressed = os.toByteArray();
    os.close();
    return new ByteArrayInputStream(compressed);
  }

  public static String decompress(InputStream is) throws IOException {
    GZIPInputStream gis = new GZIPInputStream(is, BUFFER_SIZE);
    StringBuilder string = new StringBuilder();
    byte[] data = new byte[BUFFER_SIZE];
    int bytesRead;
    while ((bytesRead = gis.read(data)) != -1) {
      string.append(new String(data, 0, bytesRead));
    }
    gis.close();
    is.close();
    return string.toString();
  }

}