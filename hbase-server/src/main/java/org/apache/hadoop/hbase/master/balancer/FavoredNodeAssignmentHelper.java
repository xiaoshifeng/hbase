/**
 *
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

package org.apache.hadoop.hbase.master.balancer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.catalog.MetaEditor;
import org.apache.hadoop.hbase.catalog.MetaReader;
import org.apache.hadoop.hbase.catalog.MetaReader.Visitor;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.master.RackManager;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.FavoredNodes;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Pair;

import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Helper class for {@link FavoredNodeLoadBalancer} that has all the intelligence
 * for racks, meta scans, etc. Instantiated by the {@link FavoredNodeLoadBalancer}
 * when needed (from within calls like
 * {@link FavoredNodeLoadBalancer#randomAssignment(HRegionInfo, List)}).
 *
 */
@InterfaceAudience.Private
public class FavoredNodeAssignmentHelper {
  private static final Log LOG = LogFactory.getLog(FavoredNodeAssignmentHelper.class);
  private RackManager rackManager;
  private Map<String, List<ServerName>> rackToRegionServerMap;
  private List<String> uniqueRackList;
  private Map<ServerName, String> regionServerToRackMap;
  private Random random;
  private List<ServerName> servers;
  public static final byte [] FAVOREDNODES_QUALIFIER = Bytes.toBytes("fn");
  public final static short FAVORED_NODES_NUM = 3;

  public FavoredNodeAssignmentHelper(final List<ServerName> servers, Configuration conf) {
    this(servers, new RackManager(conf));
  }

  public FavoredNodeAssignmentHelper(final List<ServerName> servers,
      final RackManager rackManager) {
    this.servers = servers;
    this.rackManager = rackManager;
    this.rackToRegionServerMap = new HashMap<String, List<ServerName>>();
    this.regionServerToRackMap = new HashMap<ServerName, String>();
    this.uniqueRackList = new ArrayList<String>();
    this.random = new Random();
  }

  /**
   * Perform full scan of the meta table similar to
   * {@link MetaReader#fullScan(CatalogTracker, Set, boolean)} except that this is
   * aware of the favored nodes
   * @param catalogTracker
   * @param disabledTables
   * @param excludeOfflinedSplitParents
   * @param balancer required because we need to let the balancer know about the
   * current favored nodes from meta scan
   * @return Returns a map of every region to it's currently assigned server,
   * according to META.  If the region does not have an assignment it will have
   * a null value in the map.
   * @throws IOException
   */
  public static Map<HRegionInfo, ServerName> fullScan(
      CatalogTracker catalogTracker, final Set<TableName> disabledTables,
      final boolean excludeOfflinedSplitParents,
      FavoredNodeLoadBalancer balancer) throws IOException {
    final Map<HRegionInfo, ServerName> regions =
        new TreeMap<HRegionInfo, ServerName>();
    final Map<HRegionInfo, ServerName[]> favoredNodesMap =
        new HashMap<HRegionInfo, ServerName[]>();
    Visitor v = new Visitor() {
      @Override
      public boolean visit(Result r) throws IOException {
        if (r ==  null || r.isEmpty()) return true;
        Pair<HRegionInfo, ServerName> region = HRegionInfo.getHRegionInfoAndServerName(r);
        HRegionInfo hri = region.getFirst();
        if (hri  == null) return true;
        if (hri.getTableName() == null) return true;
        if (disabledTables.contains(
            hri.getTableName())) return true;
        // Are we to include split parents in the list?
        if (excludeOfflinedSplitParents && hri.isSplitParent()) return true;
        regions.put(hri, region.getSecond());
        byte[] favoredNodes = r.getValue(HConstants.CATALOG_FAMILY,
               FavoredNodeAssignmentHelper.FAVOREDNODES_QUALIFIER);
        if (favoredNodes != null) {
          ServerName[] favoredServerList =
            FavoredNodeAssignmentHelper.getFavoredNodesList(favoredNodes);
          favoredNodesMap.put(hri, favoredServerList);
        }
        return true;
      }
    };
    MetaReader.fullScan(catalogTracker, v);
    balancer.noteFavoredNodes(favoredNodesMap);
    return regions;
  }

  public static void updateMetaWithFavoredNodesInfo(
      Map<HRegionInfo, List<ServerName>> regionToFavoredNodes,
      CatalogTracker catalogTracker) throws IOException {
    List<Put> puts = new ArrayList<Put>();
    for (Map.Entry<HRegionInfo, List<ServerName>> entry : regionToFavoredNodes.entrySet()) {
      Put put = makePutFromRegionInfo(entry.getKey(), entry.getValue());
      if (put != null) {
        puts.add(put);
      }
    }
    MetaEditor.putsToMetaTable(catalogTracker, puts);
    LOG.info("Added " + puts.size() + " regions in META");
  }

  /**
   * Generates and returns a Put containing the region info for the catalog table
   * and the servers
   * @param regionInfo
   * @param favoredNodeList
   * @return Put object
   */
  static Put makePutFromRegionInfo(HRegionInfo regionInfo, List<ServerName>favoredNodeList)
  throws IOException {
    Put put = null;
    if (favoredNodeList != null) {
      put = MetaEditor.makePutFromRegionInfo(regionInfo);
      byte[] favoredNodes = getFavoredNodes(favoredNodeList);
      put.add(HConstants.CATALOG_FAMILY, FAVOREDNODES_QUALIFIER,
          EnvironmentEdgeManager.currentTimeMillis(), favoredNodes);
      LOG.info("Create the region " + regionInfo.getRegionNameAsString() +
          " with favored nodes " + Bytes.toString(favoredNodes));
    }
    return put;
  }

  /**
   * @param favoredNodes The PB'ed bytes of favored nodes
   * @return the array of {@link ServerName} for the byte array of favored nodes.
   * @throws InvalidProtocolBufferException
   */
  public static ServerName[] getFavoredNodesList(byte[] favoredNodes)
      throws InvalidProtocolBufferException {
    FavoredNodes f = FavoredNodes.parseFrom(favoredNodes);
    List<HBaseProtos.ServerName> protoNodes = f.getFavoredNodeList();
    ServerName[] servers = new ServerName[protoNodes.size()];
    int i = 0;
    for (HBaseProtos.ServerName node : protoNodes) {
      servers[i++] = ProtobufUtil.toServerName(node);
    }
    return servers;
  }

  /**
   * @param serverList
   * @return PB'ed bytes of {@link FavoredNodes} generated by the server list.
   */
  static byte[] getFavoredNodes(List<ServerName> serverAddrList) {
    FavoredNodes.Builder f = FavoredNodes.newBuilder();
    for (ServerName s : serverAddrList) {
      HBaseProtos.ServerName.Builder b = HBaseProtos.ServerName.newBuilder();
      b.setHostName(s.getHostname());
      b.setPort(s.getPort());
      b.setStartCode(s.getStartcode());
      f.addFavoredNode(b.build());
    }
    return f.build().toByteArray();
  }

  // Place the regions round-robin across the racks picking one server from each
  // rack at a time. Start with a random rack, and a random server from every rack.
  // If a rack doesn't have enough servers it will go to the next rack and so on.
  // for choosing a primary.
  // For example, if 4 racks (r1 .. r4) with 8 servers (s1..s8) each, one possible
  // placement could be r2:s5, r3:s5, r4:s5, r1:s5, r2:s6, r3:s6..
  // If there were fewer servers in one rack, say r3, which had 3 servers, one possible
  // placement could be r2:s5, <skip-r3>, r4:s5, r1:s5, r2:s6, <skip-r3> ...
  // The regions should be distributed proportionately to the racksizes
  void placePrimaryRSAsRoundRobin(Map<ServerName, List<HRegionInfo>> assignmentMap,
      Map<HRegionInfo, ServerName> primaryRSMap, List<HRegionInfo> regions) {
    List<String> rackList = new ArrayList<String>(rackToRegionServerMap.size());
    rackList.addAll(rackToRegionServerMap.keySet());
    int rackIndex = random.nextInt(rackList.size());
    int maxRackSize = 0;
    for (Map.Entry<String,List<ServerName>> r : rackToRegionServerMap.entrySet()) {
      if (r.getValue().size() > maxRackSize) {
        maxRackSize = r.getValue().size();
      }
    }
    int numIterations = 0;
    int firstServerIndex = random.nextInt(maxRackSize);
    // Initialize the current processing host index.
    int serverIndex = firstServerIndex;
    for (HRegionInfo regionInfo : regions) {
      List<ServerName> currentServerList;
      String rackName;
      while (true) {
        rackName = rackList.get(rackIndex);
        numIterations++;
        // Get the server list for the current rack
        currentServerList = rackToRegionServerMap.get(rackName);
        
        if (serverIndex >= currentServerList.size()) { //not enough machines in this rack
          if (numIterations % rackList.size() == 0) {
            if (++serverIndex >= maxRackSize) serverIndex = 0;
          }
          if ((++rackIndex) >= rackList.size()) {
            rackIndex = 0; // reset the rack index to 0
          }
        } else break;
      }

      // Get the current process region server
      ServerName currentServer = currentServerList.get(serverIndex);

      // Place the current region with the current primary region server
      primaryRSMap.put(regionInfo, currentServer);
      List<HRegionInfo> regionsForServer = assignmentMap.get(currentServer);
      if (regionsForServer == null) {
        regionsForServer = new ArrayList<HRegionInfo>();
        assignmentMap.put(currentServer, regionsForServer);
      }
      regionsForServer.add(regionInfo);

      // Set the next processing index
      if (numIterations % rackList.size() == 0) {
        ++serverIndex;
      }
      if ((++rackIndex) >= rackList.size()) {
        rackIndex = 0; // reset the rack index to 0
      }
    }
  }

  Map<HRegionInfo, ServerName[]> placeSecondaryAndTertiaryRS(
      Map<HRegionInfo, ServerName> primaryRSMap) {
    Map<HRegionInfo, ServerName[]> secondaryAndTertiaryMap =
        new HashMap<HRegionInfo, ServerName[]>();
    for (Map.Entry<HRegionInfo, ServerName> entry : primaryRSMap.entrySet()) {
      // Get the target region and its primary region server rack
      HRegionInfo regionInfo = entry.getKey();
      ServerName primaryRS = entry.getValue();
      try {
        // Create the secondary and tertiary region server pair object.
        ServerName[] favoredNodes;
        // Get the rack for the primary region server
        String primaryRack = rackManager.getRack(primaryRS);

        if (getTotalNumberOfRacks() == 1) {
          favoredNodes = singleRackCase(regionInfo, primaryRS, primaryRack);
        } else {
          favoredNodes = multiRackCase(regionInfo, primaryRS, primaryRack);
        }
        if (favoredNodes != null) {
          secondaryAndTertiaryMap.put(regionInfo, favoredNodes);
          LOG.debug("Place the secondary and tertiary region server for region "
              + regionInfo.getRegionNameAsString());
        }
      } catch (Exception e) {
        LOG.warn("Cannot place the favored nodes for region " +
            regionInfo.getRegionNameAsString() + " because " + e);
        continue;
      }
    }
    return secondaryAndTertiaryMap;
  }

  private ServerName[] singleRackCase(HRegionInfo regionInfo,
      ServerName primaryRS,
      String primaryRack) throws IOException {
    // Single rack case: have to pick the secondary and tertiary
    // from the same rack
    List<ServerName> serverList = getServersFromRack(primaryRack);
    if (serverList.size() <= 2) {
      // Single region server case: cannot not place the favored nodes
      // on any server; !domain.canPlaceFavoredNodes()
      return null;
    } else {
      // Randomly select two region servers from the server list and make sure
      // they are not overlap with the primary region server;
     Set<ServerName> serverSkipSet = new HashSet<ServerName>();
     serverSkipSet.add(primaryRS);

     // Place the secondary RS
     ServerName secondaryRS = getOneRandomServer(primaryRack, serverSkipSet);
     // Skip the secondary for the tertiary placement
     serverSkipSet.add(secondaryRS);

     // Place the tertiary RS
     ServerName tertiaryRS =
       getOneRandomServer(primaryRack, serverSkipSet);

     if (secondaryRS == null || tertiaryRS == null) {
       LOG.error("Cannot place the secondary and terinary" +
           "region server for region " +
           regionInfo.getRegionNameAsString());
     }
     // Create the secondary and tertiary pair
     ServerName[] favoredNodes = new ServerName[2];
     favoredNodes[0] = secondaryRS;
     favoredNodes[1] = tertiaryRS;
     return favoredNodes;
    }
  }

  private ServerName[] multiRackCase(HRegionInfo regionInfo,
      ServerName primaryRS,
      String primaryRack) throws IOException {

    // Random to choose the secondary and tertiary region server
    // from another rack to place the secondary and tertiary

    // Random to choose one rack except for the current rack
    Set<String> rackSkipSet = new HashSet<String>();
    rackSkipSet.add(primaryRack);
    ServerName[] favoredNodes = new ServerName[2];
    String secondaryRack = getOneRandomRack(rackSkipSet);
    List<ServerName> serverList = getServersFromRack(secondaryRack);
    if (serverList.size() >= 2) {
      // Randomly pick up two servers from this secondary rack

      // Place the secondary RS
      ServerName secondaryRS = getOneRandomServer(secondaryRack);

      // Skip the secondary for the tertiary placement
      Set<ServerName> skipServerSet = new HashSet<ServerName>();
      skipServerSet.add(secondaryRS);
      // Place the tertiary RS
      ServerName tertiaryRS = getOneRandomServer(secondaryRack, skipServerSet);

      if (secondaryRS == null || tertiaryRS == null) {
        LOG.error("Cannot place the secondary and terinary" +
            "region server for region " +
            regionInfo.getRegionNameAsString());
      }
      // Create the secondary and tertiary pair
      favoredNodes[0] = secondaryRS;
      favoredNodes[1] = tertiaryRS;
    } else {
      // Pick the secondary rs from this secondary rack
      // and pick the tertiary from another random rack
      favoredNodes[0] = getOneRandomServer(secondaryRack);

      // Pick the tertiary
      if (getTotalNumberOfRacks() == 2) {
        // Pick the tertiary from the same rack of the primary RS
        Set<ServerName> serverSkipSet = new HashSet<ServerName>();
        serverSkipSet.add(primaryRS);
        favoredNodes[1] = getOneRandomServer(primaryRack, serverSkipSet);
      } else {
        // Pick the tertiary from another rack
        rackSkipSet.add(secondaryRack);
        String tertiaryRandomRack = getOneRandomRack(rackSkipSet);
        favoredNodes[1] = getOneRandomServer(tertiaryRandomRack);
      }
    }
    return favoredNodes;
  }

  boolean canPlaceFavoredNodes() {
    int serverSize = this.regionServerToRackMap.size();
    return (serverSize >= FAVORED_NODES_NUM);
  }

  void initialize() {
    for (ServerName sn : this.servers) {
      String rackName = this.rackManager.getRack(sn);
      List<ServerName> serverList = this.rackToRegionServerMap.get(rackName);
      if (serverList == null) {
        serverList = new ArrayList<ServerName>();
        // Add the current rack to the unique rack list
        this.uniqueRackList.add(rackName);
      }
      if (!serverList.contains(sn)) {
        serverList.add(sn);
        this.rackToRegionServerMap.put(rackName, serverList);
        this.regionServerToRackMap.put(sn, rackName);
      }
    }
  }

  private int getTotalNumberOfRacks() {
    return this.uniqueRackList.size();
  }

  private List<ServerName> getServersFromRack(String rack) {
    return this.rackToRegionServerMap.get(rack);
  }

  private ServerName getOneRandomServer(String rack,
      Set<ServerName> skipServerSet) throws IOException {
    if(rack == null) return null;
    List<ServerName> serverList = this.rackToRegionServerMap.get(rack);
    if (serverList == null) return null;

    // Get a random server except for any servers from the skip set
    if (skipServerSet != null && serverList.size() <= skipServerSet.size()) {
      throw new IOException("Cannot randomly pick another random server");
    }

    ServerName randomServer;
    do {
      int randomIndex = random.nextInt(serverList.size());
      randomServer = serverList.get(randomIndex);
    } while (skipServerSet != null && skipServerSet.contains(randomServer));

    return randomServer;
  }

  private ServerName getOneRandomServer(String rack) throws IOException {
    return this.getOneRandomServer(rack, null);
  }

  private String getOneRandomRack(Set<String> skipRackSet) throws IOException {
    if (skipRackSet == null || uniqueRackList.size() <= skipRackSet.size()) {
      throw new IOException("Cannot randomly pick another random server");
    }

    String randomRack;
    do {
      int randomIndex = random.nextInt(this.uniqueRackList.size());
      randomRack = this.uniqueRackList.get(randomIndex);
    } while (skipRackSet.contains(randomRack));

    return randomRack;
  }
}
