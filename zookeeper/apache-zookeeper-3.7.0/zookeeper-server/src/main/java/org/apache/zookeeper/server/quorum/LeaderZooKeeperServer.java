/*
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

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.management.JMException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.server.ContainerManager;
import org.apache.zookeeper.server.DataTreeBean;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.PrepRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;

/**
 *
 * Just like the standard ZooKeeperServer. We just replace the request
 * processors: PrepRequestProcessor -&gt; ProposalRequestProcessor -&gt;
 * CommitProcessor -&gt; Leader.ToBeAppliedRequestProcessor -&gt;
 * FinalRequestProcessor
 */
public class LeaderZooKeeperServer extends QuorumZooKeeperServer {

    private ContainerManager containerManager;  // guarded by sync

    CommitProcessor commitProcessor;

    PrepRequestProcessor prepRequestProcessor;

    /**
     * @throws IOException
     */
    public LeaderZooKeeperServer(FileTxnSnapLog logFactory, QuorumPeer self, ZKDatabase zkDb) throws IOException {
        super(logFactory, self.tickTime, self.minSessionTimeout, self.maxSessionTimeout, self.clientPortListenBacklog, zkDb, self);
    }

    public Leader getLeader() {
        return self.leader;
    }

    @Override
    protected void setupRequestProcessors() {
        // LeaderRequestProcessor--->PrepRequestProcessor--->ProposalRequestProcessor--->CommitProcessor--->ToBeAppliedRequestProcessor--->FinalRequestProcessor
        //                                                                           --->SyncRequestProcessor--->AckRequestProcessor
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        RequestProcessor toBeAppliedProcessor = new Leader.ToBeAppliedRequestProcessor(finalProcessor, getLeader());
        commitProcessor = new CommitProcessor(toBeAppliedProcessor, Long.toString(getServerId()), false, getZooKeeperServerListener());
        commitProcessor.start();
        ProposalRequestProcessor proposalProcessor = new ProposalRequestProcessor(this, commitProcessor);
        proposalProcessor.initialize();
        prepRequestProcessor = new PrepRequestProcessor(this, proposalProcessor);
        prepRequestProcessor.start();
        firstProcessor = new LeaderRequestProcessor(this, prepRequestProcessor);

        setupContainerManager();
    }

    private synchronized void setupContainerManager() {
        containerManager = new ContainerManager(
            getZKDatabase(),
            prepRequestProcessor,
            Integer.getInteger("znode.container.checkIntervalMs", (int) TimeUnit.MINUTES.toMillis(1)),
            Integer.getInteger("znode.container.maxPerMinute", 10000),
            Long.getLong("znode.container.maxNeverUsedIntervalMs", 0)
        );
    }

    @Override
    public synchronized void startup() {
        super.startup();
        if (containerManager != null) {
            containerManager.start();
        }
    }

    @Override
    protected void registerMetrics() {
        super.registerMetrics();

        MetricsContext rootContext = ServerMetrics.getMetrics().getMetricsProvider().getRootContext();

        rootContext.registerGauge("learners", () -> {
            return getLeader().getLearners().size();
        });
        rootContext.registerGauge("synced_followers", () -> {
            return getLeader().getForwardingFollowers().size();
        });
        rootContext.registerGauge("synced_non_voting_followers", () -> {
            return getLeader().getNonVotingFollowers().size();
        });

        rootContext.registerGauge("synced_observers", self::getSynced_observers_metric);

        rootContext.registerGauge("pending_syncs", () -> {
            return getLeader().getNumPendingSyncs();
        });
        rootContext.registerGauge("leader_uptime", () -> {
            return getLeader().getUptime();
        });
        rootContext.registerGauge("last_proposal_size", () -> {
            return getLeader().getProposalStats().getLastBufferSize();
        });
        rootContext.registerGauge("max_proposal_size", () -> {
            return getLeader().getProposalStats().getMaxBufferSize();
        });
        rootContext.registerGauge("min_proposal_size", () -> {
            return getLeader().getProposalStats().getMinBufferSize();
        });
    }

    @Override
    protected void unregisterMetrics() {
        super.unregisterMetrics();

        MetricsContext rootContext = ServerMetrics.getMetrics().getMetricsProvider().getRootContext();
        rootContext.unregisterGauge("learners");
        rootContext.unregisterGauge("synced_followers");
        rootContext.unregisterGauge("synced_non_voting_followers");
        rootContext.unregisterGauge("synced_observers");
        rootContext.unregisterGauge("pending_syncs");
        rootContext.unregisterGauge("leader_uptime");

        rootContext.unregisterGauge("last_proposal_size");
        rootContext.unregisterGauge("max_proposal_size");
        rootContext.unregisterGauge("min_proposal_size");
    }

    @Override
    public synchronized void shutdown() {
        if (containerManager != null) {
            containerManager.stop();
        }
        super.shutdown();
    }

    @Override
    public int getGlobalOutstandingLimit() {
        int divisor = self.getQuorumSize() > 2 ? self.getQuorumSize() - 1 : 1;
        int globalOutstandingLimit = super.getGlobalOutstandingLimit() / divisor;
        return globalOutstandingLimit;
    }

    @Override
    public void createSessionTracker() {
        sessionTracker = new LeaderSessionTracker(
            this,
            getZKDatabase().getSessionWithTimeOuts(),
            tickTime,
            self.getId(),
            self.areLocalSessionsEnabled(),
            getZooKeeperServerListener());
    }

    public boolean touch(long sess, int to) {
        return sessionTracker.touchSession(sess, to);
    }

    public boolean checkIfValidGlobalSession(long sess, int to) {
        if (self.areLocalSessionsEnabled() && !upgradeableSessionTracker.isGlobalSession(sess)) {
            return false;
        }
        return sessionTracker.touchSession(sess, to);
    }

    /**
     * Requests coming from the learner should go directly to
     * PrepRequestProcessor
     *
     * @param request
     */
    public void submitLearnerRequest(Request request) {
        /*
         * Requests coming from the learner should have gone through
         * submitRequest() on each server which already perform some request
         * validation, so we don't need to do it again.
         *
         * Additionally, LearnerHandler should start submitting requests into
         * the leader's pipeline only when the leader's server is started, so we
         * can submit the request directly into PrepRequestProcessor.
         *
         * This is done so that requests from learners won't go through
         * LeaderRequestProcessor which perform local session upgrade.
         */
        prepRequestProcessor.processRequest(request);
    }

    @Override
    protected void registerJMX() {
        // register with JMX
        try {
            jmxDataTreeBean = new DataTreeBean(getZKDatabase().getDataTree());
            MBeanRegistry.getInstance().register(jmxDataTreeBean, jmxServerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxDataTreeBean = null;
        }
    }

    public void registerJMX(LeaderBean leaderBean, LocalPeerBean localPeerBean) {
        // register with JMX
        if (self.jmxLeaderElectionBean != null) {
            try {
                MBeanRegistry.getInstance().unregister(self.jmxLeaderElectionBean);
            } catch (Exception e) {
                LOG.warn("Failed to register with JMX", e);
            }
            self.jmxLeaderElectionBean = null;
        }

        try {
            jmxServerBean = leaderBean;
            MBeanRegistry.getInstance().register(leaderBean, localPeerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxServerBean = null;
        }
    }

    boolean registerJMX(LearnerHandlerBean handlerBean) {
        try {
            MBeanRegistry.getInstance().register(handlerBean, jmxServerBean);
            return true;
        } catch (JMException e) {
            LOG.warn("Could not register connection", e);
        }
        return false;
    }

    @Override
    protected void unregisterJMX() {
        // unregister from JMX
        try {
            if (jmxDataTreeBean != null) {
                MBeanRegistry.getInstance().unregister(jmxDataTreeBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        jmxDataTreeBean = null;
    }

    protected void unregisterJMX(Leader leader) {
        // unregister from JMX
        try {
            if (jmxServerBean != null) {
                MBeanRegistry.getInstance().unregister(jmxServerBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        jmxServerBean = null;
    }

    @Override
    public String getState() {
        return "leader";
    }

    /**
     * Returns the id of the associated QuorumPeer, which will do for a unique
     * id of this server.
     */
    @Override
    public long getServerId() {
        return self.getId();
    }

    @Override
    protected void revalidateSession(ServerCnxn cnxn, long sessionId, int sessionTimeout) throws IOException {
        super.revalidateSession(cnxn, sessionId, sessionTimeout);
        try {
            // setowner as the leader itself, unless updated
            // via the follower handlers
            // Leader 自己的话， session owner 是ServerCnxn.me  new Object()
            setOwner(sessionId, ServerCnxn.me);
        } catch (SessionExpiredException e) {
            // this is ok, it just means that the session revalidation failed.
        }
    }

}
