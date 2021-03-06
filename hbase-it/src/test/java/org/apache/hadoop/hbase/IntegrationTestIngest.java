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

package org.apache.hadoop.hbase;

import java.io.IOException;
import java.util.Set;

import com.google.common.collect.Sets;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.LoadTestTool;
import org.apache.hadoop.util.ToolRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * A base class for tests that do something with the cluster while running
 * {@link LoadTestTool} to write and verify some data.
 */
@Category(IntegrationTests.class)
public class IntegrationTestIngest extends IntegrationTestBase {
  private static final int SERVER_COUNT = 4; // number of slaves for the smallest cluster
  private static final long DEFAULT_RUN_TIME = 20 * 60 * 1000;

  protected static String tableName = null;

  /** A soft limit on how long we should run */
  private static final String RUN_TIME_KEY = "hbase.%s.runtime";

  protected static final Log LOG = LogFactory.getLog(IntegrationTestIngest.class);
  protected IntegrationTestingUtility util;
  protected HBaseCluster cluster;
  private LoadTestTool loadTool;

  protected void setUp(int numSlavesBase) throws Exception {
    tableName = this.getClass().getSimpleName();
    util = getTestingUtil(null);
    LOG.debug("Initializing/checking cluster has " + numSlavesBase + " servers");
    util.initializeCluster(numSlavesBase);
    LOG.debug("Done initializing/checking cluster");
    cluster = util.getHBaseClusterInterface();
    deleteTableIfNecessary();
    loadTool = new LoadTestTool();
    loadTool.setConf(util.getConfiguration());
    // Initialize load test tool before we start breaking things;
    // LoadTestTool init, even when it is a no-op, is very fragile.
    int ret = loadTool.run(new String[] { "-tn", tableName, "-init_only" });
    Assert.assertEquals("Failed to initialize LoadTestTool", 0, ret);
  }

  @Override
  public void setUp() throws Exception {
    setUp(SERVER_COUNT);
  }

  @Override
  public void cleanUp() throws Exception {
    LOG.debug("Restoring the cluster");
    util.restoreCluster();
    LOG.debug("Done restoring the cluster");
  }

  @Override
  public int runTestFromCommandLine() throws Exception {
    internalRunIngestTest();
    return 0;
  }

  @Test
  public void internalRunIngestTest() throws Exception {
    runIngestTest(DEFAULT_RUN_TIME, 2500, 10, 1024, 10);
  }

  @Override
  public String getTablename() {
    return tableName;
  }

  @Override
  protected Set<String> getColumnFamilies() {
    return Sets.newHashSet(Bytes.toString(LoadTestTool.COLUMN_FAMILY));
  }

  private void deleteTableIfNecessary() throws IOException {
    if (util.getHBaseAdmin().tableExists(tableName)) {
      util.deleteTable(Bytes.toBytes(tableName));
    }
  }

  protected void runIngestTest(long defaultRunTime, int keysPerServerPerIter,
      int colsPerKey, int recordSize, int writeThreads) throws Exception {
    LOG.info("Running ingest");
    LOG.info("Cluster size:" + util.getHBaseClusterInterface().getClusterStatus().getServersSize());

    long start = System.currentTimeMillis();
    String runtimeKey = String.format(RUN_TIME_KEY, this.getClass().getSimpleName());
    long runtime = util.getConfiguration().getLong(runtimeKey, defaultRunTime);
    long startKey = 0;

    long numKeys = getNumKeys(keysPerServerPerIter);
    while (System.currentTimeMillis() - start < 0.9 * runtime) {
      LOG.info("Intended run time: " + (runtime/60000) + " min, left:" +
          ((runtime - (System.currentTimeMillis() - start))/60000) + " min");

      int ret = loadTool.run(new String[] {
          "-tn", tableName,
          "-write", String.format("%d:%d:%d", colsPerKey, recordSize, writeThreads),
          "-start_key", String.valueOf(startKey),
          "-num_keys", String.valueOf(numKeys),
          "-skip_init"
      });
      if (0 != ret) {
        String errorMsg = "Load failed with error code " + ret;
        LOG.error(errorMsg);
        Assert.fail(errorMsg);
      }

      ret = loadTool.run(new String[] {
          "-tn", tableName,
          "-update", String.format("60:%d", writeThreads),
          "-start_key", String.valueOf(startKey),
          "-num_keys", String.valueOf(numKeys),
          "-skip_init"
      });
      if (0 != ret) {
        String errorMsg = "Update failed with error code " + ret;
        LOG.error(errorMsg);
        Assert.fail(errorMsg);
      }

      ret = loadTool.run(new String[] {
          "-tn", tableName,
          "-read", "100:20",
          "-start_key", String.valueOf(startKey),
          "-num_keys", String.valueOf(numKeys),
          "-skip_init"
      });
      if (0 != ret) {
        String errorMsg = "Verification failed with error code " + ret;
        LOG.error(errorMsg);
        Assert.fail(errorMsg);
      }
      startKey += numKeys;
    }
  }

  /** Estimates a data size based on the cluster size */
  private long getNumKeys(int keysPerServer)
      throws IOException {
    int numRegionServers = cluster.getClusterStatus().getServersSize();
    return keysPerServer * numRegionServers;
  }

  public static void main(String[] args) throws Exception {
    Configuration conf = HBaseConfiguration.create();
    IntegrationTestingUtility.setUseDistributedCluster(conf);
    int ret = ToolRunner.run(conf, new IntegrationTestIngest(), args);
    System.exit(ret);
  }
}
