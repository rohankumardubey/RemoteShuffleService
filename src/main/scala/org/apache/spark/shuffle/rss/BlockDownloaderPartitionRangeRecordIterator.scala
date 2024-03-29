/*
 * Copyright (c) 2020 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.rss

import java.util
import java.util.Random
import com.uber.rss.clients._
import com.uber.rss.common.{AppShufflePartitionId, ServerDetail, ServerList}
import com.uber.rss.exceptions._
import com.uber.rss.metadata.ServiceRegistry
import com.uber.rss.metrics.M3Stats
import com.uber.rss.util.{ExceptionUtils, ThreadUtils}
import org.apache.spark.executor.ShuffleReadMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.serializer.Serializer
import org.apache.spark.shuffle.{FetchFailedException, RssServiceRegistry}

import scala.collection.JavaConverters

class BlockDownloaderPartitionRangeRecordIterator[K, C](
    user: String,
    appId: String,
    appAttempt: String,
    shuffleId: Int,
    startPartition: Int,
    endPartition: Int,
    serializer: Serializer,
    decompression: String,
    numMaps: Int,
    rssServers: ServerList,
    partitionFanout: Int,
    serviceRegistryDataCenter: String,
    serviceRegistryCluster: String,
    timeoutMillis: Int,
    maxRetryMillis: Int,
    dataAvailablePollInterval: Long,
    dataAvailableWaitTime: Long,
    shuffleReplicas: Int,
    checkShuffleReplicaConsistency: Boolean,
    shuffleReadMetrics: ShuffleReadMetrics) extends Iterator[Product2[K, C]] with Logging {

  private var currentPartition = startPartition

  private var partitionRecordIterator = createBlockDownloaderPartitionRecordIteratorWithRetry(currentPartition, timeoutMillis)

  override def hasNext: Boolean = {
    if (partitionRecordIterator.hasNext) {
      return true
    }

    if (currentPartition >= endPartition - 1) {
      return false
    }

    while (!partitionRecordIterator.hasNext && currentPartition < endPartition - 1) {
      currentPartition = currentPartition + 1
      partitionRecordIterator = createBlockDownloaderPartitionRecordIteratorWithRetry(currentPartition, timeoutMillis)
    }

    partitionRecordIterator.hasNext
  }

  override def next(): Product2[K, C] = {
    partitionRecordIterator.next()
  }

  private def createBlockDownloaderPartitionRecordIteratorWithRetry(partition: Int, retryMaxWaitMillis: Long): Iterator[Product2[K, C]] = {
    val startTime = System.currentTimeMillis()
    try {
      createBlockDownloaderPartitionRecordIteratorWithoutRetry(partition)
    } catch {
      case ex: FetchFailedException => {
        throw ex
      }
      case ex: Throwable => {
        logInfo(s"Cannot fetch shuffle $shuffleId partition $partition due to ${ExceptionUtils.getSimpleMessage(ex)}), will retry", ex)
        val elapsedTime = System.currentTimeMillis() - startTime
        retryCreateBlockDownloaderPartitionRecordIterator(partition, retryMaxWaitMillis - elapsedTime)
      }
    }
  }

  private def createBlockDownloaderPartitionRecordIteratorWithoutRetry(partition: Int): Iterator[Product2[K, C]] = {
    var downloader: ShuffleDataReader = null
    try {
      val mapOutputRssInfo = getPartitionRssInfo(partition)

      if (shuffleReplicas >= 1) {
        val serverReplicationGroups = RssUtils.getRssServerReplicationGroups(rssServers, shuffleReplicas, partition, partitionFanout)
        logInfo(s"Creating replicated read client for partition $partition, $serverReplicationGroups")
        val appShufflePartitionId = new AppShufflePartitionId(appId, appAttempt, shuffleId, partition)

        val serverConnectionResolver = new ServerConnectionStringResolver {
          override def resolveConnection(serverId: String): ServerDetail = {
            // random sleep some time to avoid request spike on service registry
            val random = new Random()
            val randomWaitMillis = random.nextInt(dataAvailablePollInterval.intValue())
            ThreadUtils.sleep(randomWaitMillis)
            val lookupResult = RssServiceRegistry.executeWithServiceRegistry(serviceRegistry =>
              serviceRegistry.lookupServers(serviceRegistryDataCenter, serviceRegistryCluster, util.Arrays.asList(serverId)))
            if (lookupResult == null) {
              throw new RssServerResolveException(s"Got null when looking up server for $serverId")
            }
            // close service registry
            if (lookupResult.size() != 1) {
              throw new RssInvalidStateException(s"Invalid result $lookupResult when looking up server for $serverId")
            }
            lookupResult.get(0)
          }
        }

        val serverConnectionRefresher = new ServerConnectionCacheUpdateRefresher(serverConnectionResolver, ServerConnectionStringCache.getInstance());
        val client = new MultiServerSocketReadClient(
          serverReplicationGroups,
          timeoutMillis,
          new ClientRetryOptions(dataAvailablePollInterval, maxRetryMillis, serverConnectionRefresher),
          user,
          appShufflePartitionId,
          new ReadClientDataOptions(JavaConverters.asJavaCollectionConverter(mapOutputRssInfo.taskAttemptIds.map(long2Long)).asJavaCollection,
            dataAvailablePollInterval,
            dataAvailableWaitTime),
          checkShuffleReplicaConsistency
        )
        client.connect()
        downloader = client
        new BlockDownloaderPartitionRecordIterator(
          shuffleId,
          partition,
          serializer,
          decompression,
          downloader,
          shuffleReadMetrics)
      } else {
        throw new RssException(s"Invalid shuffle replicas: $shuffleReplicas")
      }
    } catch {
      case ex: Throwable => {
        if (downloader != null) {
          downloader.close()
        }
        M3Stats.addException(ex, this.getClass().getSimpleName())
        throw new FetchFailedException(
          RssUtils.createReduceTaskDummyBlockManagerId(shuffleId, partition),
          shuffleId,
          -1,
          partition,
          s"Cannot fetch shuffle $shuffleId partition $partition due to ${ExceptionUtils.getSimpleMessage(ex)})",
          ex)
      }
    }
  }

  private def retryCreateBlockDownloaderPartitionRecordIterator(partition: Int, retryMaxWaitMillis: Long): Iterator[Product2[K, C]] = {
    var remainMillis = retryMaxWaitMillis
    var lastException: Throwable = null

    // TODO make this configurable
    var retryIntervalMillis = 200L
    while (remainMillis >= 0) {
      val sleepStartTime = System.currentTimeMillis()
      logInfo(s"Sleeping $retryIntervalMillis millis, and retry to create downloader iterator for shuffle $shuffleId partition $partition")
      Thread.sleep(retryIntervalMillis)
      retryIntervalMillis *= 2
      remainMillis -= (System.currentTimeMillis() - sleepStartTime)

      val retryStartTime = System.currentTimeMillis()
      try {
        return createBlockDownloaderPartitionRecordIteratorWithoutRetry(partition)
      } catch {
        case ex: FetchFailedException => {
          throw ex
        }
        case ex: Throwable => {
          lastException = ex
          logInfo(s"Retrying to create downloader iterator for shuffle $shuffleId partition $partition due to ${ExceptionUtils.getSimpleMessage(ex)})", ex)
          remainMillis -= (System.currentTimeMillis() - retryStartTime)
        }
      }
    }

    val msg = s"Timed out retrying to create downloader iterator for shuffle $shuffleId partition $partition"
    if (lastException == null) {
      throw new RssRetryTimeoutException(msg)
    } else {
      throw new RssException(msg, lastException)
    }
  }

  private def getPartitionRssInfo(partition: Int): MapOutputRssInfo = {
    logInfo(s"Fetching RSS servers from map output tracker to check with shuffle handle, shuffleId $shuffleId, partition $partition")

    val mapOutputRssInfo = RssUtils.getRssInfoFromMapOutputTracker(shuffleId, partition, dataAvailablePollInterval, maxRetryMillis)
    if (mapOutputRssInfo.numMaps != numMaps) {
      throw new RssInvalidMapStatusException(s"Invalid number of maps from map output tracker for shuffleId $shuffleId, partition $partition, expected: $numMaps, got: ${mapOutputRssInfo.numMaps}, more info: $mapOutputRssInfo")
    }

    if (mapOutputRssInfo.numRssServers != rssServers.getSeverCount) {
      throw new RssException(s"RSS servers from map output are different from shuffle handle (shuffleId $shuffleId, partition $partition): ${mapOutputRssInfo.numRssServers} <=> ${rssServers.getSeverCount}")
    }
    mapOutputRssInfo
  }
}
