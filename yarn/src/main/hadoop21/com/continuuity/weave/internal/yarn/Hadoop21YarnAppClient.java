/*
 * Copyright 2012-2013 Continuuity,Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.weave.internal.yarn;

import com.continuuity.weave.api.WeaveSpecification;
import com.continuuity.weave.internal.ProcessController;
import com.continuuity.weave.internal.ProcessLauncher;
import com.continuuity.weave.internal.appmaster.ApplicationMasterProcessLauncher;
import com.continuuity.weave.internal.appmaster.ApplicationSubmitter;
import com.continuuity.weave.yarn.utils.YarnUtils;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.AbstractIdleService;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public final class Hadoop21YarnAppClient extends AbstractIdleService implements YarnAppClient {

  private static final Logger LOG = LoggerFactory.getLogger(Hadoop21YarnAppClient.class);
  private final YarnClient yarnClient;

  public Hadoop21YarnAppClient(Configuration configuration) {
    this.yarnClient = YarnClient.createYarnClient();
    yarnClient.init(configuration);
  }

  @Override
  public ProcessLauncher<ApplicationId> createLauncher(WeaveSpecification weaveSpec) throws Exception {
    // Request for new application
    YarnClientApplication application = yarnClient.createApplication();
    final GetNewApplicationResponse response = application.getNewApplicationResponse();
    final ApplicationId appId = response.getApplicationId();

    // Setup the context for application submission
    final ApplicationSubmissionContext appSubmissionContext = application.getApplicationSubmissionContext();
    appSubmissionContext.setApplicationId(appId);
    appSubmissionContext.setApplicationName(weaveSpec.getName());

    ApplicationSubmitter submitter = new ApplicationSubmitter() {
      @Override
      public ProcessController<YarnApplicationReport> submit(YarnLaunchContext context, Resource capability) {
        ContainerLaunchContext launchContext = context.getLaunchContext();

        addRMToken(launchContext);
        appSubmissionContext.setAMContainerSpec(launchContext);
        appSubmissionContext.setResource(adjustMemory(response, capability));
        appSubmissionContext.setMaxAppAttempts(2);

        try {
          yarnClient.submitApplication(appSubmissionContext);
          return new ProcessControllerImpl(yarnClient, appId);
        } catch (Exception e) {
          LOG.error("Failed to submit application {}", appId, e);
          throw Throwables.propagate(e);
        }
      }
    };

    return new ApplicationMasterProcessLauncher(appId, submitter);
  }

  private Resource adjustMemory(GetNewApplicationResponse response, Resource capability) {
    int maxMemory = response.getMaximumResourceCapability().getMemory();
    int updatedMemory = capability.getMemory();

    if (updatedMemory > maxMemory) {
      capability.setMemory(maxMemory);
    }

    return capability;
  }

  private void addRMToken(ContainerLaunchContext context) {
    if (!UserGroupInformation.isSecurityEnabled()) {
      return;
    }

    try {
      Credentials credentials = YarnUtils.decodeCredentials(context.getTokens());

      Configuration config = yarnClient.getConfig();
      Token<TokenIdentifier> token = ConverterUtils.convertFromYarn(
        yarnClient.getRMDelegationToken(new Text(YarnUtils.getYarnTokenRenewer(config))),
        YarnUtils.getRMAddress(config));

      LOG.info("Added RM delegation token {}", token);
      credentials.addToken(token.getService(), token);

      context.setTokens(YarnUtils.encodeCredentials(credentials));

    } catch (Exception e) {
      LOG.error("Fails to create credentials.", e);
      throw Throwables.propagate(e);
    }
  }

  @Override
  public ProcessLauncher<ApplicationId> createLauncher(String user, WeaveSpecification weaveSpec) throws Exception {
    // Ignore user
    return createLauncher(weaveSpec);
  }

  @Override
  public ProcessController<YarnApplicationReport> createProcessController(ApplicationId appId) {
    return new ProcessControllerImpl(yarnClient, appId);
  }

  @Override
  protected void startUp() throws Exception {
    yarnClient.start();
  }

  @Override
  protected void shutDown() throws Exception {
    yarnClient.stop();
  }

  private static final class ProcessControllerImpl implements ProcessController<YarnApplicationReport> {
    private final YarnClient yarnClient;
    private final ApplicationId appId;

    public ProcessControllerImpl(YarnClient yarnClient, ApplicationId appId) {
      this.yarnClient = yarnClient;
      this.appId = appId;
    }

    @Override
    public YarnApplicationReport getReport() {
      try {
        return new Hadoop21YarnApplicationReport(yarnClient.getApplicationReport(appId));
      } catch (Exception e) {
        LOG.error("Failed to get application report {}", appId, e);
        throw Throwables.propagate(e);
      }
    }

    @Override
    public void cancel() {
      try {
        yarnClient.killApplication(appId);
      } catch (Exception e) {
        LOG.error("Failed to kill application {}", appId, e);
        throw Throwables.propagate(e);
      }
    }
  }
}
