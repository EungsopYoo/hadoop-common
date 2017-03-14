/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.api.records.QueueUserACLInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.server.resourcemanager.resource.ResourceWeights;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ActiveUsersManager;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerAppUtils;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;
import org.apache.hadoop.yarn.util.resource.Resources;

@Private
@Unstable
public class FSLeafQueue extends FSQueue {
  private static final Log LOG = LogFactory.getLog(
      FSLeafQueue.class.getName());

  private final List<FSAppAttempt> runnableApps = // apps that are runnable
      new ArrayList<FSAppAttempt>();
  private final List<FSAppAttempt> nonRunnableApps =
      new ArrayList<FSAppAttempt>();
  // get a lock with fair distribution for app list updates
  private final ReadWriteLock rwl = new ReentrantReadWriteLock(true);
  private final Lock readLock = rwl.readLock();
  private final Lock writeLock = rwl.writeLock();
  
  private Resource demand = Resources.createResource(0);
  
  // Variables used for preemption
  private long lastTimeAtMinShare;
  private long lastTimeAtFairShareThreshold;
  
  // Track the AM resource usage for this queue
  private Resource amResourceUsage;

  private final ActiveUsersManager activeUsersManager;
  public static final List<FSQueue> EMPTY_LIST = Collections.emptyList();

  public FSLeafQueue(String name, FairScheduler scheduler,
      FSParentQueue parent) {
    super(name, scheduler, parent);
    this.lastTimeAtMinShare = scheduler.getClock().getTime();
    this.lastTimeAtFairShareThreshold = scheduler.getClock().getTime();
    activeUsersManager = new ActiveUsersManager(getMetrics());
    amResourceUsage = Resource.newInstance(0, 0);
    getMetrics().setAMResourceUsage(amResourceUsage);
  }
  
  public void addApp(FSAppAttempt app, boolean runnable) {
    writeLock.lock();
    try {
      if (runnable) {
        runnableApps.add(app);
      } else {
        nonRunnableApps.add(app);
      }
    } finally {
      writeLock.unlock();
    }
  }
  
  // for testing
  void addAppSchedulable(FSAppAttempt appSched) {
    writeLock.lock();
    try {
      runnableApps.add(appSched);
    } finally {
      writeLock.unlock();
    }
  }
  
  /**
   * Removes the given app from this queue.
   * @return whether or not the app was runnable
   */
  public boolean removeApp(FSAppAttempt app) {
    boolean runnable = false;

    // Remove app from runnable/nonRunnable list while holding the write lock
    writeLock.lock();
    try {
      runnable = runnableApps.remove(app);
      if (!runnable) {
        // removeNonRunnableApp acquires the write lock again, which is fine
        if (!removeNonRunnableApp(app)) {
          throw new IllegalStateException("Given app to remove " + app +
              " does not exist in queue " + this);
        }
      }
    } finally {
      writeLock.unlock();
    }

    // Update AM resource usage if needed. If isAMRunning is true, we're not
    // running an unmanaged AM.
    if (runnable && app.isAmRunning()) {
      Resources.subtractFrom(amResourceUsage, app.getAMResource());
      getMetrics().setAMResourceUsage(amResourceUsage);
    }

    return runnable;
  }

  /**
   * Removes the given app if it is non-runnable and belongs to this queue
   * @return true if the app is removed, false otherwise
   */
  public boolean removeNonRunnableApp(FSAppAttempt app) {
    writeLock.lock();
    try {
      return nonRunnableApps.remove(app);
    } finally {
      writeLock.unlock();
    }
  }

  public boolean isRunnableApp(FSAppAttempt attempt) {
    readLock.lock();
    try {
      return runnableApps.contains(attempt);
    } finally {
      readLock.unlock();
    }
  }

  public boolean isNonRunnableApp(FSAppAttempt attempt) {
    readLock.lock();
    try {
      return nonRunnableApps.contains(attempt);
    } finally {
      readLock.unlock();
    }
  }

  public void resetPreemptedResources() {
    readLock.lock();
    try {
      for (FSAppAttempt attempt : runnableApps) {
        attempt.resetPreemptedResources();
      }
    } finally {
      readLock.unlock();
    }
  }

  public void clearPreemptedResources() {
    readLock.lock();
    try {
      for (FSAppAttempt attempt : runnableApps) {
        attempt.clearPreemptedResources();
      }
    } finally {
      readLock.unlock();
    }
  }

  public List<FSAppAttempt> getCopyOfNonRunnableAppSchedulables() {
    List<FSAppAttempt> appsToReturn = new ArrayList<FSAppAttempt>();
    readLock.lock();
    try {
      appsToReturn.addAll(nonRunnableApps);
    } finally {
      readLock.unlock();
    }
    return appsToReturn;
  }

  @Override
  public void collectSchedulerApplications(
      Collection<ApplicationAttemptId> apps) {
    readLock.lock();
    try {
      for (FSAppAttempt appSched : runnableApps) {
        apps.add(appSched.getApplicationAttemptId());
      }
      for (FSAppAttempt appSched : nonRunnableApps) {
        apps.add(appSched.getApplicationAttemptId());
      }
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public void setPolicy(SchedulingPolicy policy)
      throws AllocationConfigurationException {
    if (!SchedulingPolicy.isApplicableTo(policy, SchedulingPolicy.DEPTH_LEAF)) {
      throwPolicyDoesnotApplyException(policy);
    }
    super.policy = policy;
  }
  
  @Override
  public void recomputeShares() {
    readLock.lock();
    try {
      policy.computeShares(runnableApps, getFairShare());
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public Resource getDemand() {
    return demand;
  }

  @Override
  public Resource getResourceUsage() {
    Resource usage = Resources.createResource(0);
    readLock.lock();
    try {
      for (FSAppAttempt app : runnableApps) {
        Resources.addTo(usage, app.getResourceUsage());
      }
      for (FSAppAttempt app : nonRunnableApps) {
        Resources.addTo(usage, app.getResourceUsage());
      }
    } finally {
      readLock.unlock();
    }
    return usage;
  }

  public Resource getAmResourceUsage() {
    return amResourceUsage;
  }

  @Override
  public void updateDemand() {
    // Compute demand by iterating through apps in the queue
    // Limit demand to maxResources
    Resource maxRes = scheduler.getAllocationConfiguration()
        .getMaxResources(getName());
    demand = Resources.createResource(0);
    readLock.lock();
    try {
      for (FSAppAttempt sched : runnableApps) {
        if (Resources.equals(demand, maxRes)) {
          break;
        }
        updateDemandForApp(sched, maxRes);
      }
      for (FSAppAttempt sched : nonRunnableApps) {
        if (Resources.equals(demand, maxRes)) {
          break;
        }
        updateDemandForApp(sched, maxRes);
      }
    } finally {
      readLock.unlock();
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("The updated demand for " + getName() + " is " + demand
          + "; the max is " + maxRes);
      LOG.debug("The updated fairshare for " + getName() + " is "
          + getFairShare());
    }
  }
  
  private void updateDemandForApp(FSAppAttempt sched, Resource maxRes) {
    sched.updateDemand();
    Resource toAdd = sched.getDemand();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Counting resource from " + sched.getName() + " " + toAdd
          + "; Total resource consumption for " + getName() + " now "
          + demand);
    }
    demand = Resources.add(demand, toAdd);
    demand = Resources.componentwiseMin(demand, maxRes);
  }

  @Override
  public Resource assignContainer(FSSchedulerNode node) {
    Resource assigned = Resources.none();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Node " + node.getNodeName() + " offered to queue: " +
          getName() + " fairShare: " + getFairShare());
    }

    if (!assignContainerPreCheck(node)) {
      return assigned;
    }

    Comparator<Schedulable> comparator = policy.getComparator();
    writeLock.lock();
    try {
      Collections.sort(runnableApps, comparator);
    } finally {
      writeLock.unlock();
    }
    // Release write lock here for better performance and avoiding deadlocks.
    // runnableApps can be in unsorted state because of this section,
    // but we can accept it in practice since the probability is low.
    readLock.lock();
    try {
      for (FSAppAttempt sched : runnableApps) {
        if (SchedulerAppUtils.isBlacklisted(sched, node, LOG)) {
          continue;
        }

        assigned = sched.assignContainer(node);
        if (!assigned.equals(Resources.none())) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Assigned container in queue:" + getName() + " " +
                "container:" + assigned);
          }
          break;
        }
      }
    } finally {
      readLock.unlock();
    }
    return assigned;
  }

  @Override
  public RMContainer preemptContainer() {
    RMContainer toBePreempted = null;

    // If this queue is not over its fair share, reject
    if (!canBePreempted()) {
      return toBePreempted;
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Queue " + getName() + " is going to preempt a container " +
          "from its applications.");
    }

    // Choose the app that is most over fair share
    Comparator<Schedulable> comparator = policy.getComparator();
    FSAppAttempt candidateSched = null;
    readLock.lock();
    try {
      for (FSAppAttempt sched : runnableApps) {
        if (candidateSched == null ||
            comparator.compare(sched, candidateSched) > 0) {
          candidateSched = sched;
        }
      }
    } finally {
      readLock.unlock();
    }

    // Preempt from the selected app
    if (candidateSched != null) {
      toBePreempted = candidateSched.preemptContainer();
    }
    return toBePreempted;
  }

  @Override
  public List<FSQueue> getChildQueues() {
    return EMPTY_LIST;
  }
  
  @Override
  public List<QueueUserACLInfo> getQueueUserAclInfo(UserGroupInformation user) {
    QueueUserACLInfo userAclInfo =
      recordFactory.newRecordInstance(QueueUserACLInfo.class);
    List<QueueACL> operations = new ArrayList<QueueACL>();
    for (QueueACL operation : QueueACL.values()) {
      if (hasAccess(operation, user)) {
        operations.add(operation);
      }
    }

    userAclInfo.setQueueName(getQueueName());
    userAclInfo.setUserAcls(operations);
    return Collections.singletonList(userAclInfo);
  }
  
  public long getLastTimeAtMinShare() {
    return lastTimeAtMinShare;
  }

  private void setLastTimeAtMinShare(long lastTimeAtMinShare) {
    this.lastTimeAtMinShare = lastTimeAtMinShare;
  }

  public long getLastTimeAtFairShareThreshold() {
    return lastTimeAtFairShareThreshold;
  }

  private void setLastTimeAtFairShareThreshold(
      long lastTimeAtFairShareThreshold) {
    this.lastTimeAtFairShareThreshold = lastTimeAtFairShareThreshold;
  }

  @Override
  public int getNumRunnableApps() {
    readLock.lock();
    try {
      return runnableApps.size();
    } finally {
      readLock.unlock();
    }
  }

  public int getNumNonRunnableApps() {
    readLock.lock();
    try {
      return nonRunnableApps.size();
    } finally {
      readLock.unlock();
    }
  }

  public int getNumPendingApps() {
    int numPendingApps = 0;
    readLock.lock();
    try {
      for (FSAppAttempt attempt : runnableApps) {
        if (attempt.isPending()) {
          numPendingApps++;
        }
      }
      numPendingApps += nonRunnableApps.size();
    } finally {
      readLock.unlock();
    }
    return numPendingApps;
  }

  /**
   * TODO: Based on how frequently this is called, we might want to club
   * counting pending and active apps in the same method.
   */
  public int getNumActiveApps() {
    int numActiveApps = 0;
    readLock.lock();
    try {
      for (FSAppAttempt attempt : runnableApps) {
        if (!attempt.isPending()) {
          numActiveApps++;
        }
      }
    } finally {
      readLock.unlock();
    }
    return numActiveApps;
  }

  @Override
  public ActiveUsersManager getActiveUsersManager() {
    return activeUsersManager;
  }

  /**
   * Compute the maximum resource AM can use. The value is the result of
   * multiplying FairShare and maxAMShare. If FairShare is zero, use
   * min(maxShare, available resource) instead to prevent zero value for
   * maximum AM resource since it forbids any job running in the queue.
   *
   * @return the maximum resource AM can use
   */
  private Resource computeMaxAMResource() {
    float maxAMShare =
        scheduler.getAllocationConfiguration().getQueueMaxAMShare(getName());
    Resource maxResource = Resources.clone(getFairShare());
    if (maxResource.getMemory() == 0) {
      maxResource.setMemory(
          Math.min(scheduler.getRootQueueMetrics().getAvailableMB(),
                   getMaxShare().getMemory()));
    }

    if (maxResource.getVirtualCores() == 0) {
      maxResource.setVirtualCores(Math.min(
          scheduler.getRootQueueMetrics().getAvailableVirtualCores(),
          getMaxShare().getVirtualCores()));
    }

    // Round up to allow AM to run when there is only one vcore on the cluster
    return Resources.multiplyAndRoundUp(maxResource, maxAMShare);
  }

  /**
   * Check whether this queue can run the Application Master under the
   * maxAMShare limit.
   *
   * @param amResource resources required to run the AM
   * @return true if this queue can run
   */
  public boolean canRunAppAM(Resource amResource) {
    float maxAMShare =
        scheduler.getAllocationConfiguration().getQueueMaxAMShare(getName());
    if (Math.abs(maxAMShare - -1.0f) < 0.0001) {
      return true;
    }

    Resource maxAMResource = computeMaxAMResource();
    getMetrics().setMaxAMShare(maxAMResource);
    Resource ifRunAMResource = Resources.add(amResourceUsage, amResource);
    return Resources.fitsIn(ifRunAMResource, maxAMResource);
  }

  public void addAMResourceUsage(Resource amResource) {
    if (amResource != null) {
      Resources.addTo(amResourceUsage, amResource);
      getMetrics().setAMResourceUsage(amResourceUsage);
    }
  }

  @Override
  public void recoverContainer(Resource clusterResource,
      SchedulerApplicationAttempt schedulerAttempt, RMContainer rmContainer) {
    // TODO Auto-generated method stub
  }

  /**
   * Update the preemption fields for the queue, i.e. the times since last was
   * at its guaranteed share and over its fair share threshold.
   */
  public void updateStarvationStats() {
    long now = scheduler.getClock().getTime();
    if (!isStarvedForMinShare()) {
      setLastTimeAtMinShare(now);
    }
    if (!isStarvedForFairShare()) {
      setLastTimeAtFairShareThreshold(now);
    }
  }

  /**
   * Is a queue being starved for its min share.
   */
  @VisibleForTesting
  boolean isStarvedForMinShare() {
    return isStarved(getMinShare());
  }

  /**
   * Is a queue being starved for its fair share threshold.
   */
  @VisibleForTesting
  boolean isStarvedForFairShare() {
    return isStarved(
        Resources.multiply(getFairShare(), getFairSharePreemptionThreshold()));
  }

  private boolean isStarved(Resource share) {
    Resource desiredShare = Resources.min(policy.getResourceCalculator(),
            scheduler.getClusterResource(), share, getDemand());
    Resource resourceUsage = getResourceUsage();
    return Resources.lessThan(policy.getResourceCalculator(),
            scheduler.getClusterResource(), resourceUsage, desiredShare);
  }

  @Override
  protected void dumpStateInternal(StringBuilder sb) {
    ResourceWeights weights =
        scheduler.getAllocationConfiguration().getQueueWeight(getName());
    Resource maxShare =
        scheduler.getAllocationConfiguration().getMaxResources(getName());
    Resource minShare =
        scheduler.getAllocationConfiguration().getMinResources(getName());
    float maxAMShare=
        scheduler.getAllocationConfiguration().getQueueMaxAMShare(getName());

    sb.append("{Name: " + getName() +
        ", Weight: " + weights +
        ", Policy: " + policy.getName() +
        ", FairShare: " + getFairShare() +
        ", SteadyFairShare: " + getSteadyFairShare() +
        ", MaxShare: " + maxShare +
        ", MinShare: " + minShare +
        ", ResourceUsage: " + getResourceUsage() +
        ", Demand: " + getDemand() +
        ", Runnable: " + getNumRunnableApps() +
        ", NumPendingApps: " + getNumPendingApps() +
        ", NonRunnable: " + getNumNonRunnableApps() +
        ", MaxAMShare: " + maxAMShare +
        ", MaxAMResource: " + computeMaxAMResource() +
        ", AMResourceUsage: " + getAmResourceUsage() +
        ", LastTimeAtMinShare: " + lastTimeAtMinShare +
        "}");
  }
}
