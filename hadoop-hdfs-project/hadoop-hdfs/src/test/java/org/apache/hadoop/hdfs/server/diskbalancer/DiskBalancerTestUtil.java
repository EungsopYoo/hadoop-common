/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.hdfs.server.diskbalancer;

import com.google.common.base.Preconditions;
import org.apache.hadoop.hdfs.StorageType;
import org.apache.hadoop.hdfs.server.diskbalancer.connectors.NullConnector;
import org.apache.hadoop.hdfs.server.diskbalancer.datamodel.DiskBalancerCluster;
import org.apache.hadoop.hdfs.server.diskbalancer.datamodel.DiskBalancerDataNode;
import org.apache.hadoop.hdfs.server.diskbalancer.datamodel.DiskBalancerVolume;
import org.apache.hadoop.hdfs.server.diskbalancer.datamodel.DiskBalancerVolumeSet;
import org.apache.hadoop.util.Time;

import java.util.Random;
import java.util.UUID;

/**
 * Helper class to create various cluster configrations at run time.
 */
public class DiskBalancerTestUtil {
  public static final long MB = 1024 * 1024L;
  public static final long GB = MB * 1024L;
  public static final long TB = GB * 1024L;
  private static int[] diskSizes =
      {1, 2, 3, 4, 5, 6, 7, 8, 9, 100, 200, 300, 400, 500, 600, 700, 800, 900};
  Random rand;
  private String stringTable =
      "ABCDEDFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0987654321";

  /**
   * Constructs a util class.
   */
  public DiskBalancerTestUtil() {
    this.rand = new Random(Time.monotonicNow());
  }

  /**
   * Returns a random string.
   *
   * @param length - Number of chars in the string
   *
   * @return random String
   */
  private String getRandomName(int length) {
    StringBuilder name = new StringBuilder();
    for (int x = 0; x < length; x++) {
      name.append(stringTable.charAt(rand.nextInt(stringTable.length())));
    }
    return name.toString();
  }

  /**
   * Returns a Random Storage Type.
   *
   * @return - StorageType
   */
  private StorageType getRandomStorageType() {
    return StorageType.parseStorageType(rand.nextInt(3));
  }

  /**
   * Returns random capacity, if the size is smaller than 10
   * they are TBs otherwise the size is assigned to GB range.
   *
   * @return Long - Disk Size
   */
  private long getRandomCapacity() {
    int size = diskSizes[rand.nextInt(diskSizes.length)];
    if (size < 10) {
      return size * TB;
    } else {
      return size * GB;
    }
  }

  /**
   * Some value under 20% in these tests.
   */
  private long getRandomReserved(long capacity) {
    double rcap = capacity * 0.2d;
    double randDouble = rand.nextDouble();
    double temp = randDouble * rcap;
    return (new Double(temp)).longValue();

  }

  /**
   * Some value less that capacity - reserved.
   */
  private long getRandomDfsUsed(long capacity, long reserved) {
    double rcap = capacity - reserved;
    double randDouble = rand.nextDouble();
    double temp = randDouble * rcap;
    return (new Double(temp)).longValue();
  }

  /**
   * Creates a Random Volume of a specific storageType.
   *
   * @return Volume
   */
  public DiskBalancerVolume createRandomVolume() {
    return createRandomVolume(getRandomStorageType());
  }

  /**
   * Creates a Random Volume for testing purpose.
   *
   * @param type - StorageType
   *
   * @return DiskBalancerVolume
   */
  public DiskBalancerVolume createRandomVolume(StorageType type) {
    DiskBalancerVolume volume = new DiskBalancerVolume();
    volume.setPath("/tmp/disk/" + getRandomName(10));
    volume.setStorageType(type.toString());
    volume.setTransient(type.isTransient());

    volume.setCapacity(getRandomCapacity());
    volume.setReserved(getRandomReserved(volume.getCapacity()));
    volume
        .setUsed(getRandomDfsUsed(volume.getCapacity(), volume.getReserved()));
    volume.setUuid(UUID.randomUUID().toString());
    return volume;
  }

  /**
   * Creates a RandomVolumeSet.
   *
   * @param type -Storage Type
   * @param diskCount - How many disks you need.
   *
   * @return volumeSet
   *
   * @throws Exception
   */
  public DiskBalancerVolumeSet createRandomVolumeSet(StorageType type,
                                                     int diskCount)
      throws Exception {

    Preconditions.checkState(diskCount > 0);
    DiskBalancerVolumeSet volumeSet =
        new DiskBalancerVolumeSet(type.isTransient());
    for (int x = 0; x < diskCount; x++) {
      volumeSet.addVolume(createRandomVolume(type));
    }
    assert (volumeSet.getVolumeCount() == diskCount);
    return volumeSet;
  }

  /**
   * Creates a RandomDataNode.
   *
   * @param diskTypes - Storage types needed in the Node
   * @param diskCount - Disk count - that many disks of each type is created
   *
   * @return DataNode
   *
   * @throws Exception
   */
  public DiskBalancerDataNode createRandomDataNode(StorageType[] diskTypes,
                                                   int diskCount)
      throws Exception {
    Preconditions.checkState(diskTypes.length > 0);
    Preconditions.checkState(diskCount > 0);

    DiskBalancerDataNode node =
        new DiskBalancerDataNode(UUID.randomUUID().toString());

    for (StorageType t : diskTypes) {
      DiskBalancerVolumeSet vSet = createRandomVolumeSet(t, diskCount);
      for (DiskBalancerVolume v : vSet.getVolumes()) {
        node.addVolume(v);
      }
    }
    return node;
  }

  /**
   * Creates a RandomCluster.
   *
   * @param dataNodeCount - How many nodes you need
   * @param diskTypes - StorageTypes you need in each node
   * @param diskCount - How many disks you need of each type.
   *
   * @return Cluster
   *
   * @throws Exception
   */
  public DiskBalancerCluster createRandCluster(int dataNodeCount,
                                               StorageType[] diskTypes,
                                               int diskCount)

      throws Exception {
    Preconditions.checkState(diskTypes.length > 0);
    Preconditions.checkState(diskCount > 0);
    Preconditions.checkState(dataNodeCount > 0);
    NullConnector nullConnector = new NullConnector();
    DiskBalancerCluster cluster = new DiskBalancerCluster(nullConnector);

    // once we add these nodes into the connector, cluster will read them
    // from the connector.
    for (int x = 0; x < dataNodeCount; x++) {
      nullConnector.addNode(createRandomDataNode(diskTypes, diskCount));
    }

    // with this call we have populated the cluster info
    cluster.readClusterInfo();
    return cluster;
  }

}
