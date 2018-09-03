/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.stream.processor.core.ha;

import io.netty.buffer.ByteBuf;
import org.apache.commons.pool.impl.GenericKeyedObjectPool;
import org.apache.log4j.Logger;
import org.wso2.carbon.cluster.coordinator.service.ClusterCoordinator;
import org.wso2.carbon.databridge.commons.ServerEventListener;
import org.wso2.carbon.stream.processor.core.DeploymentMode;
import org.wso2.carbon.stream.processor.core.NodeInfo;
import org.wso2.carbon.stream.processor.core.event.queue.EventListMapManager;
import org.wso2.carbon.stream.processor.core.ha.tcp.TCPServer;
import org.wso2.carbon.stream.processor.core.ha.transport.TCPClientPool;
import org.wso2.carbon.stream.processor.core.ha.transport.TCPClientPoolFactory;
import org.wso2.carbon.stream.processor.core.internal.StreamProcessorDataHolder;
import org.wso2.carbon.stream.processor.core.internal.beans.DeploymentConfig;
import org.wso2.carbon.stream.processor.core.internal.beans.TCPClientPoolConfig;
import org.wso2.siddhi.core.SiddhiAppRuntime;
import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.core.exception.CannotRestoreSiddhiAppStateException;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Class that manages Active and Passive nodes in a 2 node minimum HA configuration
 */
public class HAManager {

    private ClusterCoordinator clusterCoordinator;
    private boolean isActiveNode;
    private String nodeId;
    private String clusterId;
    private int recordTableQueueCapacity;
    private HACoordinationSourceHandlerManager sourceHandlerManager;
    private HACoordinationSinkHandlerManager sinkHandlerManager;
    private HACoordinationRecordTableHandlerManager recordTableHandlerManager;
    private TCPServer tcpServerInstance = TCPServer.getInstance();
    private EventListMapManager eventListMapManager;
    private DeploymentConfig deploymentConfig;
    private BlockingQueue<ByteBuf> eventByteBufferQueue;
    private TCPClientPoolConfig tcpClientPoolConfig;

    private final static Map<String, Object> activeNodePropertiesMap = new HashMap<>();
    private static final Logger log = Logger.getLogger(HAManager.class);

    public HAManager(ClusterCoordinator clusterCoordinator, String nodeId, String clusterId,
                     DeploymentConfig deploymentConfig) {
        this.clusterCoordinator = clusterCoordinator;
        this.nodeId = nodeId;
        this.clusterId = clusterId;
        this.eventListMapManager = new EventListMapManager();
        this.eventByteBufferQueue = new LinkedBlockingQueue<>(deploymentConfig.
                getEventByteBufferQueueCapacity());
        this.deploymentConfig = deploymentConfig;
        this.tcpClientPoolConfig = deploymentConfig.getTcpClientPoolConfig();
        this.recordTableQueueCapacity = deploymentConfig.getRecordTableQueueCapacity();
    }

    public void start() {
        sourceHandlerManager = new HACoordinationSourceHandlerManager(getTCPClientConnectionPool());
        sinkHandlerManager = new HACoordinationSinkHandlerManager();
        recordTableHandlerManager = new HACoordinationRecordTableHandlerManager(recordTableQueueCapacity);

        StreamProcessorDataHolder.setSinkHandlerManager(sinkHandlerManager);
        StreamProcessorDataHolder.setSourceHandlerManager(sourceHandlerManager);
        StreamProcessorDataHolder.setRecordTableHandlerManager(recordTableHandlerManager);
        SiddhiManager siddhiManager = StreamProcessorDataHolder.getSiddhiManager();

        siddhiManager.setSourceHandlerManager(StreamProcessorDataHolder.getSourceHandlerManager());
        siddhiManager.setSinkHandlerManager(StreamProcessorDataHolder.getSinkHandlerManager());
        siddhiManager.setRecordTableHandlerManager(StreamProcessorDataHolder.getRecordTableHandlerManager());

        clusterCoordinator.registerEventListener(new HAEventListener());

        //Give time for the cluster to normalize
        while (clusterCoordinator.getLeaderNode() == null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                log.warn("Error in waiting for leader node");
            }
        }

        isActiveNode = clusterCoordinator.isLeaderNode();

        if (isActiveNode) {
            log.info("HA Deployment: Starting up as Active Node");
            clusterCoordinator.setPropertiesMap(activeNodePropertiesMap);
            isActiveNode = true;
        } else {
            log.info("HA Deployment: Starting up as Passive Node");
            //initialize passive queue
            EventListMapManager.initializeEventListMap();

            //start tcp server
            tcpServerInstance.start(deploymentConfig.getTcpServerConfigs(), eventByteBufferQueue);
        }

        NodeInfo nodeInfo = StreamProcessorDataHolder.getNodeInfo();
        nodeInfo.setMode(DeploymentMode.MINIMUM_HA);
        nodeInfo.setNodeId(nodeId);
        nodeInfo.setGroupId(clusterId);
        nodeInfo.setActiveNode(isActiveNode);
    }

    /**
     * Stops TCP server and other resources
     * Sync state from persisted information
     * Playback events
     * Start siddhi app runtimes
     * Start databridge servers
     */
    void changeToActive() {
        if (!isActiveNode) {
            isActiveNode = true;
            tcpServerInstance.stop();
            syncState();

            //Give time for byte buffer queue to be empty
            while (eventByteBufferQueue.peek() != null) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    log.warn("Error in checking byte buffer queue empty");
                }
            }
            tcpServerInstance.clearResources();
            //change the system clock to work with event time
            enableEventTimeClock(true);
            try {
                eventListMapManager.trimAndSendToInputHandler();
            } catch (InterruptedException e) {
                log.warn("Error in sending events to input handler." + e.getMessage());
            }

            //change the system clock to work with current time
            enableEventTimeClock(false);
            startSiddhiAppRuntimes();

            //start the databridge servers
            List<ServerEventListener> listeners = StreamProcessorDataHolder.getServerListeners();
            for (ServerEventListener listener : listeners) {
                listener.start();
            }
            NodeInfo nodeInfo = StreamProcessorDataHolder.getNodeInfo();
            nodeInfo.setActiveNode(isActiveNode);
            if (log.isDebugEnabled()) {
                log.debug("Successfully Changed to Active Mode ");
            }
        }
    }

    /**
     * Start TCP server
     * Initialize eventEventListMap
     */
    void changeToPassive() {
        isActiveNode = false;
        //stop the databridge servers
        List<ServerEventListener> listeners = StreamProcessorDataHolder.getServerListeners();
        for (ServerEventListener listener : listeners) {
            listener.stop();
        }
        stopSiddhiAppRuntimes();
        //initialize event list map
        EventListMapManager.initializeEventListMap();

        NodeInfo nodeInfo = StreamProcessorDataHolder.getNodeInfo();
        nodeInfo.setActiveNode(isActiveNode);
        //start tcp server
        tcpServerInstance.start(deploymentConfig.getTcpServerConfigs(), eventByteBufferQueue);
        if (log.isDebugEnabled()) {
            log.debug("Successfully Changed to Passive Mode ");
        }
    }

    private void syncState() {
        ConcurrentMap<String, SiddhiAppRuntime> siddhiAppRuntimeMap
                = StreamProcessorDataHolder.getSiddhiManager().getSiddhiAppRuntimeMap();

        siddhiAppRuntimeMap.forEach((siddhiAppName, siddhiAppRuntime) -> {
            if (log.isDebugEnabled()) {
                log.debug("Restoring state of Siddhi Application " +
                        siddhiAppRuntime.getName());
            }
            try {
                siddhiAppRuntime.restoreLastRevision();
                StreamProcessorDataHolder.getNodeInfo().setLastSyncedTimestamp(System.currentTimeMillis());
                StreamProcessorDataHolder.getNodeInfo().setInSync(true);
            } catch (CannotRestoreSiddhiAppStateException e) {
                log.error("Error in restoring Siddhi Application: " + siddhiAppRuntime.getName(), e);
            }
        });
        if (log.isDebugEnabled()) {
            log.debug("Successfully Synced the state ");
        }
    }

    private void enableEventTimeClock(boolean enablePlayBack) {
        ConcurrentMap<String, SiddhiAppRuntime> siddhiAppRuntimeMap
                = StreamProcessorDataHolder.getSiddhiManager().getSiddhiAppRuntimeMap();

        siddhiAppRuntimeMap.forEach((siddhiAppName, siddhiAppRuntime) -> {
            if (log.isDebugEnabled()) {
                log.debug("Changing system clock for Siddhi Application " +
                        siddhiAppRuntime.getName());
            }
            siddhiAppRuntime.enablePlayBack(enablePlayBack, null, null);
        });
        if (log.isDebugEnabled()) {
            log.debug("Changed event play back mode = " + enablePlayBack);
        }
    }

    private void startSiddhiAppRuntimes() {
        ConcurrentMap<String, SiddhiAppRuntime> siddhiAppRuntimeMap
                = StreamProcessorDataHolder.getSiddhiManager().getSiddhiAppRuntimeMap();

        siddhiAppRuntimeMap.forEach((siddhiAppName, siddhiAppRuntime) -> {
            if (log.isDebugEnabled()) {
                log.debug("Starting Siddhi Application " +
                        siddhiAppRuntime.getName());
            }
            siddhiAppRuntime.start();
        });
    }

    private void stopSiddhiAppRuntimes() {
        ConcurrentMap<String, SiddhiAppRuntime> siddhiAppRuntimeMap
                = StreamProcessorDataHolder.getSiddhiManager().getSiddhiAppRuntimeMap();

        siddhiAppRuntimeMap.forEach((siddhiAppName, siddhiAppRuntime) -> {
            if (log.isDebugEnabled()) {
                log.debug("Stopping Siddhi Application " +
                        siddhiAppRuntime.getName());
            }
            siddhiAppRuntime.shutdown();
        });
    }

    private GenericKeyedObjectPool getTCPClientConnectionPool() {
        TCPClientPool tcpClientPool = new TCPClientPool();
        TCPClientPoolFactory tcpClientPoolFactory = new TCPClientPoolFactory(deploymentConfig.getPassiveNodeHost(),
                deploymentConfig.getPassiveNodePort());
        return tcpClientPool.getClientPool(tcpClientPoolFactory, tcpClientPoolConfig.getMaxActive(), tcpClientPoolConfig
                .getMaxIdle(), tcpClientPoolConfig.isTestOnBorrow(), tcpClientPoolConfig
                .getTimeBetweenEvictionRunsMillis(), tcpClientPoolConfig.getMinEvictableIdleTimeMillis());
    }

    public boolean isActiveNode() {
        return isActiveNode;
    }

    public static Map<String, Object> getActiveNodePropertiesMap() {
        return activeNodePropertiesMap;
    }
}
