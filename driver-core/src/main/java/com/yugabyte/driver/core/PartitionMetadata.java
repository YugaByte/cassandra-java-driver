// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
package com.yugabyte.driver.core;

import com.datastax.driver.core.AggregateMetadata;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.FunctionMetadata;
import com.datastax.driver.core.Host;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.MaterializedViewMetadata;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.SchemaChangeListener;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.UserType;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The table partition metadata cache of all tables in a cluster. For each table, it tracks the
 * start-key to tablet leader/followers mapping of all partitions of the table. The whole cache is
 * refreshed at regular intervals or triggered when a node or table is added or removed.
 */
public class PartitionMetadata implements Host.StateListener, SchemaChangeListener {

  // Query to load partition metadata for all tables.
  public static final String PARTITIONS_QUERY =
      "select keyspace_name, table_name, start_key, replica_addresses " +
      "from system_schema.partitions;";

  private static final Logger logger = LoggerFactory.getLogger(PartitionMetadata.class);

  // The session for loading partition metadata.
  private volatile Session session;

  // The table partition map.
  private volatile Map<TableMetadata, NavigableMap<Integer, List<Host>>> tableMap;

  // The future to track the scheduled refresh.
  private volatile ScheduledFuture<?> loadFuture;

  // The cluster medatata.
  private volatile Metadata clusterMetadata;

  // The set of hosts in the cluster and those that are up.
  private final Set<Host> allHosts;
  private final Set<Host> upHosts;

  // Refresh frequency in seconds.
  private final int refreshFrequencySeconds;

  // Load count (for testing purpose only).
  final AtomicInteger loadCount;

  // Scheduler to refresh partition metadata shared across all instances of PartitionMetadata.
  private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  /**
   * Creates a new {@code PartitionMetadata}.
   *
   * @param cluster                  the cluster
   * @param refreshFrequencySeconds  the refresh frequency in seconds
   */
  public PartitionMetadata(Cluster cluster, int refreshFrequencySeconds) {
    this.allHosts = new HashSet<Host>();
    this.upHosts = new HashSet<Host>();
    this.refreshFrequencySeconds = refreshFrequencySeconds;
    this.loadCount = new AtomicInteger();
    cluster.register((Host.StateListener)this);
    cluster.register((SchemaChangeListener)this);
  }

  /**
   * Extracts an unsigned 16-bit number from a {@code ByteBuffer}.
   */
  private static int getKey(ByteBuffer bb) {
    int key = (bb.remaining() == 0) ? 0 : bb.getShort();
    // Flip the negative values back to positive.
    return (key >= 0) ? key : key + 0x10000;
  }

  /**
   * Loads the table partition metadata.
   */
  private void loadAsync() {
    Futures.addCallback(session.executeAsync(PARTITIONS_QUERY),
                        new FutureCallback<ResultSet>() {
                          public void onSuccess(ResultSet rs) {
                            load(rs);
                          }
                          public void onFailure(Throwable t) {
                            logger.error("execute failed", t);
                          }
                        });
  }

  /**
   * Loads the table partition metadata from the result set.
   */
  private void load(ResultSet rs) {
    Map<TableMetadata, NavigableMap<Integer, List<Host>>> tableMap =
        new HashMap<TableMetadata, NavigableMap<Integer, List<Host>>>();
    for (Row row : rs) {
      KeyspaceMetadata keyspace = clusterMetadata.getKeyspace(row.getString("keyspace_name"));
      if (keyspace == null) {
        logger.debug("Keyspace " + row.getString("keyspace_name") +
                     " not found in cluster metadata");
        continue;
      }
      TableMetadata table = keyspace.getTable(row.getString("table_name"));
      if (table == null) {
        logger.debug("Table " + row.getString("table_name") + " not found in " +
                     keyspace.getName() + " keyspace metadata");
        continue;
      }
      NavigableMap<Integer, List<Host>> partitionMap = tableMap.get(table);
      if (partitionMap == null) {
        partitionMap = new TreeMap<Integer, List<Host>>();
        tableMap.put(table, partitionMap);
      }

      Map<InetAddress, String> replicaAddresses = row.getMap("replica_addresses",
                                                             InetAddress.class,
                                                             String.class);

      // Prepare the host map to look up host by the inet address.
      Map<InetAddress, Host> hostMap = new HashMap<InetAddress, Host>();
      for (Host host : clusterMetadata.getAllHosts()) {
        hostMap.put(host.getAddress(), host);
      }

      List<Host> hosts = new Vector<Host>();
      for (Map.Entry<InetAddress, String> entry : replicaAddresses.entrySet()) {
        Host host = hostMap.get(entry.getKey());
        if (host == null) {
          // Ignore tables in system keyspaces because they are hosted in master not tserver.
          if (!isSystem(keyspace)) {
            logger.debug("Host " + entry.getKey() + " not found in cluster metadata for table " +
                         keyspace.getName() + "." + table.getName());
          }
          continue;
        }
        // Put the leader at the beginning and the rest after.
        String role = entry.getValue();
        if (role.equals("LEADER")) {
          hosts.add(0, host);
        } else if (role.equals("FOLLOWER")) {
          hosts.add(host);
        }
      }
      int startKey = getKey(row.getBytes("start_key"));
      partitionMap.put(startKey, hosts);
    }

    // Make the result available.
    this.tableMap = tableMap;
    loadCount.incrementAndGet();

    scheduleNextLoad();
  }

  /**
   * Returns if a keyspace is a system keyspace.
   */
  private static boolean isSystem(KeyspaceMetadata keyspace) {
    String ksname = keyspace.getName();
    return (ksname.equals("system")             ||
            ksname.equals("system_auth")        ||
            ksname.equals("system_distributed") ||
            ksname.equals("system_schema")      ||
            ksname.equals("system_traces"));
  }

  /**
   * Schedules the next metadata load.
   */
  private synchronized void scheduleNextLoad() {
    // If another load has been scheduled, cancel it first and do not proceed if it is already
    // running (not done) and cannot be canceled. In that case, just let it finish. We want to
    // cancel the scheduled load and reschedule again at a later time since we have just refreshed.
    // And we do not want to have 2 recurring refresh cycles in parallel.
    if (refreshFrequencySeconds > 0) {
      if (loadFuture != null && !loadFuture.cancel(false) && !loadFuture.isDone()) {
        return;
      }

      loadFuture = scheduler.schedule(
          new Runnable() { public void run() { loadAsync(); } },
          refreshFrequencySeconds, TimeUnit.SECONDS);
    }
  }

  /**
   * Returns the hosts for the partition key in the given table.
   *
   * @param table  the table
   * @param key    the partition key
   * @return       the hosts for the partition key, or an empty list if they cannot be determined
   *               due to cases like stale metadata cache
   */
  public List<Host> getHostsForKey(TableMetadata table, int key) {
    if (tableMap != null) {
      NavigableMap<Integer, List<Host>> partitionMap = tableMap.get(table);
      if (partitionMap != null) {
        List<Host> hosts = partitionMap.floorEntry(key).getValue();
        logger.debug("key " + key + " -> hosts = " + Arrays.toString(hosts.toArray()));
        return hosts;
      }
    }
    return new Vector<Host>();
  }

  @Override
  public void onAdd(Host host) {
    if (allHosts.add(host)) {
      loadAsync();
    }
  }

  @Override
  public void onUp(Host host) {
    if (upHosts.add(host)) {
      loadAsync();
    }
  }

  @Override
  public void onDown(Host host) {
    if (upHosts.remove(host)) {
      loadAsync();
    }
  }

  @Override
  public void onRemove(Host host) {
    if (allHosts.remove(host)) {
      loadAsync();
    }
  }

  @Override
  public void onKeyspaceAdded(KeyspaceMetadata keyspace) {
  }

  @Override
  public void onKeyspaceRemoved(KeyspaceMetadata keyspace) {
  }

  @Override
  public void onKeyspaceChanged(KeyspaceMetadata current, KeyspaceMetadata previous) {
  }

  @Override
  public void onTableAdded(TableMetadata table) {
    try {
      if (tableMap == null || !tableMap.containsKey(table)) {
        loadAsync();
      }
    } catch (Exception e) {
      logger.error("onTableAdded failed", e);
    }
  }

  @Override
  public void onTableRemoved(TableMetadata table) {
    try {
      if (tableMap == null || tableMap.containsKey(table)) {
        loadAsync();
      }
    } catch (Exception e) {
      logger.error("onTableRemoved failed", e);
    }
  }

  @Override
  public void onTableChanged(TableMetadata current, TableMetadata previous) {
  }

  @Override
  public void onUserTypeAdded(UserType type) {
  }

  @Override
  public void onUserTypeRemoved(UserType type) {
  }

  @Override
  public void onUserTypeChanged(UserType current, UserType previous) {
  }

  @Override
  public void onFunctionAdded(FunctionMetadata function) {
  }

  @Override
  public void onFunctionRemoved(FunctionMetadata function) {
  }

  @Override
  public void onFunctionChanged(FunctionMetadata current, FunctionMetadata previous) {
  }

  @Override
  public void onAggregateAdded(AggregateMetadata aggregate) {
  }

  @Override
  public void onAggregateRemoved(AggregateMetadata aggregate) {
  }

  @Override
  public void onAggregateChanged(AggregateMetadata current, AggregateMetadata previous) {
  }

  @Override
  public void onMaterializedViewAdded(MaterializedViewMetadata view) {
  }

  @Override
  public void onMaterializedViewRemoved(MaterializedViewMetadata view) {
  }

  @Override
  public void onMaterializedViewChanged(MaterializedViewMetadata current,
                                        MaterializedViewMetadata previous) {
  }

  @Override
  public void onRegister(Cluster cluster) {
    session = cluster.newSession();
    clusterMetadata = cluster.getMetadata();
    scheduleNextLoad();
  }

  @Override
  public void onUnregister(Cluster cluster) {
    if (loadFuture != null) {
      loadFuture.cancel(true);
      loadFuture = null;
    }
    if (session != null) {
      session.close();
      session = null;
    }
  }
}
