/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.watcher.execution.TriggeredWatchStore;
import org.elasticsearch.xpack.watcher.watch.Watch;
import org.junit.Before;
import org.mockito.stubbing.Answer;

import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;

import static java.util.Arrays.asList;
import static org.elasticsearch.cluster.routing.ShardRoutingState.RELOCATING;
import static org.elasticsearch.cluster.routing.ShardRoutingState.STARTED;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WatcherLifeCycleServiceTests extends ESTestCase {

    private ClusterService clusterService;
    private WatcherService watcherService;
    private WatcherLifeCycleService lifeCycleService;

    @Before
    public void prepareServices() {
        ThreadPool threadPool = mock(ThreadPool.class);
        final ExecutorService executorService = EsExecutors.newDirectExecutorService();
        when(threadPool.executor(anyString())).thenReturn(executorService);
        clusterService = mock(ClusterService.class);
        Answer<Object> answer = invocationOnMock -> {
            AckedClusterStateUpdateTask updateTask = (AckedClusterStateUpdateTask) invocationOnMock.getArguments()[1];
            updateTask.onAllNodesAcked(null);
            return null;
        };
        doAnswer(answer).when(clusterService).submitStateUpdateTask(anyString(), any(ClusterStateUpdateTask.class));
        watcherService = mock(WatcherService.class);
        lifeCycleService = new WatcherLifeCycleService(Settings.EMPTY, threadPool, clusterService, watcherService);
    }

    public void testStartAndStopCausedByClusterState() throws Exception {
        IndexRoutingTable indexRoutingTable = IndexRoutingTable.builder(new Index("anything", "foo")).build();
        ClusterState previousClusterState = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(newNode("node_1")))
                .routingTable(RoutingTable.builder().add(indexRoutingTable).build())
                .build();

        IndexRoutingTable watchRoutingTable = IndexRoutingTable.builder(new Index(Watch.INDEX, "foo")).build();
        ClusterState clusterState = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(newNode("node_1")))
                .routingTable(RoutingTable.builder().add(watchRoutingTable).build())
                .build();

        when(watcherService.state()).thenReturn(WatcherState.STOPPED);
        when(watcherService.validate(clusterState)).thenReturn(true);
        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterState, previousClusterState));
        verify(watcherService, times(1)).start(clusterState);
        verify(watcherService, never()).stop(anyString());

        // Trying to start a second time, but that should have no affect.
        when(watcherService.state()).thenReturn(WatcherState.STARTED);
        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterState, previousClusterState));
        verify(watcherService, times(1)).start(clusterState);
        verify(watcherService, never()).stop(anyString());
    }

    public void testStartWithStateNotRecoveredBlock() throws Exception {
        DiscoveryNodes.Builder nodes = new DiscoveryNodes.Builder().masterNodeId("id1").localNodeId("id1");
        ClusterState clusterState = ClusterState.builder(new ClusterName("my-cluster"))
                .blocks(ClusterBlocks.builder().addGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK))
                .nodes(nodes).build();
        when(watcherService.state()).thenReturn(WatcherState.STOPPED);
        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterState, clusterState));
        verify(watcherService, never()).start(any(ClusterState.class));
    }

    public void testManualStartStop() throws Exception {
        IndexRoutingTable watchRoutingTable = IndexRoutingTable.builder(new Index(Watch.INDEX, "foo")).build();
        ClusterState clusterState = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(newNode("node_1")))
                .routingTable(RoutingTable.builder().add(watchRoutingTable).build())
                .build();

        when(clusterService.state()).thenReturn(clusterState);
        when(watcherService.validate(clusterState)).thenReturn(true);

        when(watcherService.state()).thenReturn(WatcherState.STOPPED);
        lifeCycleService.start();
        verify(watcherService, times(1)).start(any(ClusterState.class));
        verify(watcherService, never()).stop(anyString());

        when(watcherService.state()).thenReturn(WatcherState.STARTED);
        String reason = randomAlphaOfLength(10);
        lifeCycleService.stop(reason);
        verify(watcherService, times(1)).start(any(ClusterState.class));
        verify(watcherService, times(1)).stop(eq(reason));

        // Starting via cluster state update, we shouldn't start because we have been stopped manually.
        when(watcherService.state()).thenReturn(WatcherState.STOPPED);
        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterState, clusterState));
        verify(watcherService, times(2)).start(any(ClusterState.class));
        verify(watcherService, times(1)).stop(eq(reason));

        // no change, keep going
        clusterState  = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(newNode("node_1")))
                .build();
        when(watcherService.state()).thenReturn(WatcherState.STARTED);
        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterState, clusterState));
        verify(watcherService, times(2)).start(any(ClusterState.class));
        verify(watcherService, times(1)).stop(eq(reason));

        ClusterState previousClusterState = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(newNode("node_1")))
                .build();
        when(watcherService.validate(clusterState)).thenReturn(true);
        when(watcherService.state()).thenReturn(WatcherState.STOPPED);
        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterState, previousClusterState));
        verify(watcherService, times(3)).start(any(ClusterState.class));
        verify(watcherService, times(1)).stop(eq(reason));
    }

    public void testManualStartStopClusterStateNotValid() throws Exception {
        DiscoveryNodes.Builder nodes = new DiscoveryNodes.Builder().masterNodeId("id1").localNodeId("id1");
        ClusterState clusterState = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(nodes).build();
        when(clusterService.state()).thenReturn(clusterState);
        when(watcherService.state()).thenReturn(WatcherState.STOPPED);
        when(watcherService.validate(clusterState)).thenReturn(false);

        lifeCycleService.start();
        verify(watcherService, never()).start(any(ClusterState.class));
        verify(watcherService, never()).stop(anyString());
    }

    public void testManualStartStopWatcherNotStopped() throws Exception {
        DiscoveryNodes.Builder nodes = new DiscoveryNodes.Builder().masterNodeId("id1").localNodeId("id1");
        ClusterState clusterState = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(nodes).build();
        when(clusterService.state()).thenReturn(clusterState);
        when(watcherService.state()).thenReturn(WatcherState.STOPPING);

        lifeCycleService.start();
        verify(watcherService, never()).validate(any(ClusterState.class));
        verify(watcherService, never()).start(any(ClusterState.class));
        verify(watcherService, never()).stop(anyString());
    }

    public void testNoLocalShards() throws Exception {
        Index watchIndex = new Index(Watch.INDEX, "foo");
        ShardId shardId = new ShardId(watchIndex, 0);
        IndexRoutingTable watchRoutingTable = IndexRoutingTable.builder(watchIndex)
                .addShard(TestShardRouting.newShardRouting(shardId, "node_2", true, randomFrom(STARTED, RELOCATING)))
                .build();
        DiscoveryNodes nodes = new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1")
                .add(newNode("node_1")).add(newNode("node_2"))
                .build();
        IndexMetaData indexMetaData = IndexMetaData.builder(Watch.INDEX)
                .settings(Settings.builder()
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                ).build();

        ClusterState clusterState = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(nodes)
                .routingTable(RoutingTable.builder().add(watchRoutingTable).build())
                .metaData(MetaData.builder().put(indexMetaData, false))

                .build();

        when(watcherService.state()).thenReturn(WatcherState.STARTED);

        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterState, clusterState));
        verify(watcherService).pauseExecution(eq("no local watcher shards"));
    }

    public void testReplicaWasAddedOrRemoved() throws Exception {
        Index watchIndex = new Index(Watch.INDEX, "foo");
        ShardId shardId = new ShardId(watchIndex, 0);
        ShardId secondShardId = new ShardId(watchIndex, 1);
        DiscoveryNodes discoveryNodes = new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1")
                .add(newNode("node_1"))
                .add(newNode("node_2"))
                .build();

        IndexRoutingTable previousWatchRoutingTable = IndexRoutingTable.builder(watchIndex)
                .addShard(TestShardRouting.newShardRouting(secondShardId, "node_1", true, randomFrom(STARTED, RELOCATING)))
                .addShard(TestShardRouting.newShardRouting(shardId, "node_2", true, randomFrom(STARTED, RELOCATING)))
                .build();

        IndexMetaData indexMetaData = IndexMetaData.builder(Watch.INDEX)
                .settings(Settings.builder()
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                ).build();

        ClusterState stateWithPrimaryShard = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(discoveryNodes)
                .routingTable(RoutingTable.builder().add(previousWatchRoutingTable).build())
                .metaData(MetaData.builder().put(indexMetaData, false))
                .build();

        IndexRoutingTable currentWatchRoutingTable = IndexRoutingTable.builder(watchIndex)
                .addShard(TestShardRouting.newShardRouting(shardId, "node_1", false, randomFrom(STARTED, RELOCATING)))
                .addShard(TestShardRouting.newShardRouting(secondShardId, "node_1", true, randomFrom(STARTED, RELOCATING)))
                .addShard(TestShardRouting.newShardRouting(shardId, "node_2", true, STARTED))
                .build();

        ClusterState stateWithReplicaAdded = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(discoveryNodes)
                .routingTable(RoutingTable.builder().add(currentWatchRoutingTable).build())
                .metaData(MetaData.builder().put(indexMetaData, false))
                .build();

        // randomize between addition or removal of a replica
        boolean replicaAdded = randomBoolean();
        ClusterChangedEvent event;
        ClusterState usedClusterState;
        if (replicaAdded) {
            event = new ClusterChangedEvent("any", stateWithReplicaAdded, stateWithPrimaryShard);
            usedClusterState = stateWithReplicaAdded;
        } else {
            event = new ClusterChangedEvent("any", stateWithPrimaryShard, stateWithReplicaAdded);
            usedClusterState = stateWithPrimaryShard;
        }

        when(watcherService.state()).thenReturn(WatcherState.STARTED);
        lifeCycleService.clusterChanged(event);
        verify(watcherService).reload(eq(usedClusterState), anyString());
    }

    // make sure that cluster state changes can be processed on nodes that do not hold data
    public void testNonDataNode() {
        Index index = new Index(Watch.INDEX, "foo");
        ShardId shardId = new ShardId(index, 0);
        ShardRouting shardRouting = TestShardRouting.newShardRouting(shardId, "node2", true, STARTED);
        IndexRoutingTable.Builder indexRoutingTable = IndexRoutingTable.builder(index).addShard(shardRouting);

        DiscoveryNode node1 = new DiscoveryNode("node_1", ESTestCase.buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(asList(randomFrom(DiscoveryNode.Role.INGEST, DiscoveryNode.Role.MASTER))), Version.CURRENT);

        DiscoveryNode node2 = new DiscoveryNode("node_2", ESTestCase.buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(asList(DiscoveryNode.Role.DATA)), Version.CURRENT);

        DiscoveryNode node3 = new DiscoveryNode("node_3", ESTestCase.buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(asList(DiscoveryNode.Role.DATA)), Version.CURRENT);

        IndexMetaData.Builder indexMetaDataBuilder = IndexMetaData.builder(Watch.INDEX)
                .settings(Settings.builder()
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                );

        ClusterState previousState = ClusterState.builder(new ClusterName("my-cluster"))
                .metaData(MetaData.builder().put(indexMetaDataBuilder))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(node1).add(node2).add(node3))
                .routingTable(RoutingTable.builder().add(indexRoutingTable).build())
                .build();

        IndexMetaData.Builder newIndexMetaDataBuilder = IndexMetaData.builder(Watch.INDEX)
                .settings(Settings.builder()
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
                        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                );

        ShardRouting replicaShardRouting = TestShardRouting.newShardRouting(shardId, "node3", false, STARTED);
        IndexRoutingTable.Builder newRoutingTable = IndexRoutingTable.builder(index).addShard(shardRouting).addShard(replicaShardRouting);
        ClusterState currentState = ClusterState.builder(new ClusterName("my-cluster"))
                .metaData(MetaData.builder().put(newIndexMetaDataBuilder))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(node1).add(node2).add(node3))
                .routingTable(RoutingTable.builder().add(newRoutingTable).build())
                .build();

        when(watcherService.state()).thenReturn(WatcherState.STARTED);
        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", currentState, previousState));
        verify(watcherService, times(0)).pauseExecution(anyObject());
        verify(watcherService, times(0)).reload(any(), any());
    }

    public void testThatMissingWatcherIndexMetadataOnlyResetsOnce() {
        Index watchIndex = new Index(Watch.INDEX, "foo");
        ShardId shardId = new ShardId(watchIndex, 0);
        IndexRoutingTable watchRoutingTable = IndexRoutingTable.builder(watchIndex)
                .addShard(TestShardRouting.newShardRouting(shardId, "node_1", true, STARTED)).build();
        DiscoveryNodes nodes = new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(newNode("node_1")).build();

        IndexMetaData.Builder newIndexMetaDataBuilder = IndexMetaData.builder(Watch.INDEX)
                .settings(Settings.builder()
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                );

        ClusterState clusterStateWithWatcherIndex = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(nodes)
                .routingTable(RoutingTable.builder().add(watchRoutingTable).build())
                .metaData(MetaData.builder().put(newIndexMetaDataBuilder))
                .build();

        ClusterState clusterStateWithoutWatcherIndex = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(nodes)
                .build();

        when(watcherService.state()).thenReturn(WatcherState.STARTED);

        // first add the shard allocation ids, by going from empty cs to CS with watcher index
        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterStateWithWatcherIndex, clusterStateWithoutWatcherIndex));

        // now remove watches index, and ensure that pausing is only called once, no matter how often called (i.e. each CS update)
        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterStateWithoutWatcherIndex, clusterStateWithWatcherIndex));
        verify(watcherService, times(1)).pauseExecution(anyObject());

        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterStateWithoutWatcherIndex, clusterStateWithWatcherIndex));
        verify(watcherService, times(1)).pauseExecution(anyObject());
    }

    public void testWatcherDoesNotStartWithOldIndexFormat() throws Exception {
        String index = randomFrom(Watch.INDEX, TriggeredWatchStore.INDEX_NAME);
        Index watchIndex = new Index(index, "foo");
        ShardId shardId = new ShardId(watchIndex, 0);
        IndexRoutingTable watchRoutingTable = IndexRoutingTable.builder(watchIndex)
                .addShard(TestShardRouting.newShardRouting(shardId, "node_1", true, STARTED)).build();
        DiscoveryNodes nodes = new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(newNode("node_1")).build();

        Settings.Builder indexSettings = Settings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT);
        // no matter if not set or set to one, watcher should not start
        if (randomBoolean()) {
            indexSettings.put(IndexMetaData.INDEX_FORMAT_SETTING.getKey(), 1);
        }
        IndexMetaData.Builder newIndexMetaDataBuilder = IndexMetaData.builder(index).settings(indexSettings);

        ClusterState clusterStateWithWatcherIndex = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(nodes)
                .routingTable(RoutingTable.builder().add(watchRoutingTable).build())
                .metaData(MetaData.builder().put(newIndexMetaDataBuilder))
                .build();

        ClusterState emptyClusterState = ClusterState.builder(new ClusterName("my-cluster")).nodes(nodes).build();

        when(watcherService.state()).thenReturn(WatcherState.STOPPED);
        when(watcherService.validate(eq(clusterStateWithWatcherIndex))).thenReturn(true);
        lifeCycleService.clusterChanged(new ClusterChangedEvent("any", clusterStateWithWatcherIndex, emptyClusterState));
        verify(watcherService, never()).start(any(ClusterState.class));
    }

    private static DiscoveryNode newNode(String nodeName) {
        return new DiscoveryNode(nodeName, ESTestCase.buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(asList(DiscoveryNode.Role.values())), Version.CURRENT);
    }
}
