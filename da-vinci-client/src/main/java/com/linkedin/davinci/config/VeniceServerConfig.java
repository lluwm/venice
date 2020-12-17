package com.linkedin.davinci.config;

import com.linkedin.davinci.store.rocksdb.RocksDBServerConfig;
import com.linkedin.venice.exceptions.ConfigurationException;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.kafka.admin.ScalaAdminUtils;
import com.linkedin.venice.meta.IngestionMode;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.utils.queues.FairBlockingQueue;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.linkedin.venice.ConfigKeys.*;
import static com.linkedin.davinci.config.BlockingQueueType.*;


/**
 * class that maintains config very specific to a Venice server
 */
public class VeniceServerConfig extends VeniceClusterConfig {
  /**
   * Since the RT topic could be consumed by multiple store versions for Hybrid stores, we couldn't share the consumer across
   * different Hybrid store versions.
   * Considering there will be at most 3 store versions (backup, current and new), we need to make sure the consumer pool
   * size should be at least 3.
   */
  public static final int MINIMUM_CONSUMER_NUM_IN_CONSUMER_POOL_PER_KAFKA_CLUSTER = 3;

  private final int listenerPort;
  private final RocksDBServerConfig rocksDBServerConfig;
  private final boolean enableServerWhiteList;
  private final boolean autoCreateDataPath; // default true
  /**
   * Maximum number of thread that the thread pool would keep to run the Helix online offline state transition.
   * The thread pool would create a thread for a state transition until the number of thread equals to this number.
   */
  private final int maxOnlineOfflineStateTransitionThreadNumber;

  /**
   *  Maximum number of thread that the thread pool would keep to run the Helix leader follower state transition.
   */
  private final int maxLeaderFollowerStateTransitionThreadNumber;

  /**
   * Thread number of store writers, which will process all the incoming records from all the topics.
   */
  private final int storeWriterNumber;

  /**
   * Buffer capacity being used by each writer.
   * We need to be careful when tuning this param.
   * If the queue capacity is too small, the throughput will be impacted greatly,
   * and if it is too big, the memory usage used by buffered queue could be potentially high.
   * The overall memory usage is: {@link #storeWriterNumber} * {@link #storeWriterBufferMemoryCapacity}
   */
  private final long storeWriterBufferMemoryCapacity;

  /**
   * Considering the consumer thread could put various sizes of messages into the shared queue, the internal
   * {@link com.linkedin.venice.kafka.consumer.MemoryBoundBlockingQueue} won't notify the waiting thread (consumer thread)
   * right away when some message gets processed until the freed memory hit the follow config: {@link #storeWriterBufferNotifyDelta}.
   * The reason behind this design:
   * When the buffered queue is full, and the processing thread keeps processing small message, the bigger message won't
   * have chance to get queued into the buffer since the memory freed by the processed small message is not enough to
   * fit the bigger message.
   *
   * With this delta config, {@link com.linkedin.venice.kafka.consumer.MemoryBoundBlockingQueue} will guarantee some fairness
   * among various sizes of messages when buffered queue is full.
   *
   * When tuning this config, we need to consider the following tradeoffs:
   * 1. {@link #storeWriterBufferNotifyDelta} must be smaller than {@link #storeWriterBufferMemoryCapacity};
   * 2. If the delta is too big, it will waste some buffer space since it won't notify the waiting threads even there
   * are some memory available (less than the delta);
   * 3. If the delta is too small, the big message may not be able to get chance to be buffered when the queue is full;
   *
   */
  private final long storeWriterBufferNotifyDelta;

  /**
   * The number of threads being used to serve get requests.
   */
  private final int restServiceStorageThreadNum;

  /**
   * Idle timeout for Storage Node Netty connections.
   */
  private final int nettyIdleTimeInSeconds;

  /**
   * Max request size for request to Storage Node.
   */
  private final int maxRequestSize;

  /**
   * Time interval for offset check of topic in Hybrid Store lag measurement.
   */
  private final int topicOffsetCheckIntervalMs;

  /**
   * Graceful shutdown period.
   * Venice SN needs to explicitly do graceful shutdown since Netty's graceful shutdown logic
   * will close all the connections right away, which is not expected.
   */
  private final int nettyGracefulShutdownPeriodSeconds;

  /**
   * number of worker threads for the netty listener.  If not specified, netty uses twice cpu count.
   */
  private final int nettyWorkerThreadCount;

  private final long databaseSyncBytesIntervalForTransactionalMode;

  private final long databaseSyncBytesIntervalForDeferredWriteMode;

  private final double diskFullThreshold;

  private final int partitionGracefulDropDelaySeconds;

  private final long leakedResourceCleanUpIntervalInMS;

  private final boolean readOnlyForBatchOnlyStoreEnabled;

  private final boolean quotaEnforcementEnabled;

  private final long nodeCapacityInRcu;

  private final int kafkaMaxPollRecords;

  private final int kafkaPollRetryTimes;

  private final int kafkaPollRetryBackoffMs;

  /**
   * The number of threads being used to serve compute request.
   */
  private final int serverComputeThreadNum;

  /**
   * Health check cycle in server.
   */
  private final long diskHealthCheckIntervalInMS;

  /**
   * Server disk health check timeout.
   */
  private final long diskHealthCheckTimeoutInMs;

  private final boolean diskHealthCheckServiceEnabled;

  private final boolean computeFastAvroEnabled;

  private final long participantMessageConsumptionDelayMs;

  /**
   * Feature flag for hybrid quota, default false
   */
  private final boolean hybridQuotaEnabled;

  /**
   * When a server replica is promoted to leader from standby, it wait for some time after the last message consumed
   * before it switches to the leader role.
   */
  private final long serverPromotionToLeaderReplicaDelayMs;

  private final boolean enableParallelBatchGet;

  private final int parallelBatchGetChunkSize;

  private final boolean keyValueProfilingEnabled;

  private final boolean enableDatabaseMemoryStats;

  private final Map<String, Integer> storeToEarlyTerminationThresholdMSMap;

  private final int databaseLookupQueueCapacity;
  private final int computeQueueCapacity;
  private final BlockingQueueType blockingQueueType;
  private final boolean restServiceEpollEnabled;
  private final String kafkaAdminClass;
  private final boolean kafkaOpenSSLEnabled;
  private final long routerConnectionWarmingDelayMs;
  private boolean helixOfflinePushEnabled;
  private boolean helixHybridStoreQuotaEnabled;
  private final long ssdHealthCheckShutdownTimeMs;
  private final boolean sharedConsumerPoolEnabled;
  private final int consumerPoolSizePerKafkaCluster;
  private final boolean leakedResourceCleanupEnabled;
  private final boolean cacheWarmingBeforeReadyToServeEnabled;
  private final Set<String> cacheWarmingStoreSet;
  private final int cacheWarmingThreadPoolSize;
  private final long delayReadyToServeMS;

  private final IngestionMode ingestionMode;
  private final int ingestionServicePort;
  private final int ingestionApplicationPort;
  private final boolean databaseChecksumVerificationEnabled;
  private final boolean rocksDbStorageEngineConfigCheckEnabled;

  private final VeniceProperties kafkaConsumerConfigsForLocalConsumption;
  private final VeniceProperties kafkaConsumerConfigsForRemoteConsumption;

  private final boolean freezeIngestionIfReadyToServeOrLocalDataExists;

  private final String systemSchemaClusterName;

  private final long sharedConsumerNonExistingTopicCleanupDelayMS;

  public VeniceServerConfig(VeniceProperties serverProperties) throws ConfigurationException {
    super(serverProperties);
    listenerPort = serverProperties.getInt(LISTENER_PORT, 0);
    dataBasePath = serverProperties.getString(DATA_BASE_PATH,
        Paths.get(System.getProperty("java.io.tmpdir"), "venice-server-data").toAbsolutePath().toString());
    autoCreateDataPath = Boolean.valueOf(serverProperties.getString(AUTOCREATE_DATA_PATH, "true"));
    rocksDBServerConfig = new RocksDBServerConfig(serverProperties);
    enableServerWhiteList = serverProperties.getBoolean(ENABLE_SERVER_WHITE_LIST, false);
    maxOnlineOfflineStateTransitionThreadNumber = serverProperties.getInt(MAX_ONLINE_OFFLINE_STATE_TRANSITION_THREAD_NUMBER, 100);
    maxLeaderFollowerStateTransitionThreadNumber = serverProperties.getInt(MAX_LEADER_FOLLOWER_STATE_TRANSITION_THREAD_NUMBER, 20);
    storeWriterNumber = serverProperties.getInt(STORE_WRITER_NUMBER, 8);
    // To miminize the GC impact during heavy ingestion.
    storeWriterBufferMemoryCapacity = serverProperties.getSizeInBytes(STORE_WRITER_BUFFER_MEMORY_CAPACITY, 10 * 1024 * 1024); // 10MB
    storeWriterBufferNotifyDelta = serverProperties.getSizeInBytes(STORE_WRITER_BUFFER_NOTIFY_DELTA, 1 * 1024 * 1024); // 1MB
    restServiceStorageThreadNum = serverProperties.getInt(SERVER_REST_SERVICE_STORAGE_THREAD_NUM, 16);
    serverComputeThreadNum = serverProperties.getInt(SERVER_COMPUTE_THREAD_NUM, 16);
    nettyIdleTimeInSeconds = serverProperties.getInt(SERVER_NETTY_IDLE_TIME_SECONDS, (int) TimeUnit.HOURS.toSeconds(3)); // 3 hours
    maxRequestSize = (int)serverProperties.getSizeInBytes(SERVER_MAX_REQUEST_SIZE, 256 * 1024); // 256KB
    topicOffsetCheckIntervalMs = serverProperties.getInt(SERVER_SOURCE_TOPIC_OFFSET_CHECK_INTERVAL_MS, (int) TimeUnit.SECONDS.toMillis(60));
    nettyGracefulShutdownPeriodSeconds = serverProperties.getInt(SERVER_NETTY_GRACEFUL_SHUTDOWN_PERIOD_SECONDS, 30); //30 seconds
    nettyWorkerThreadCount = serverProperties.getInt(SERVER_NETTY_WORKER_THREADS, 0);

    databaseSyncBytesIntervalForTransactionalMode = serverProperties.getSizeInBytes(SERVER_DATABASE_SYNC_BYTES_INTERNAL_FOR_TRANSACTIONAL_MODE, 32 * 1024 * 1024); // 32MB
    databaseSyncBytesIntervalForDeferredWriteMode = serverProperties.getSizeInBytes(SERVER_DATABASE_SYNC_BYTES_INTERNAL_FOR_DEFERRED_WRITE_MODE, 60 * 1024 * 1024); // 60MB
    diskFullThreshold = serverProperties.getDouble(SERVER_DISK_FULL_THRESHOLD, 0.95);
    partitionGracefulDropDelaySeconds = serverProperties.getInt(SERVER_PARTITION_GRACEFUL_DROP_DELAY_IN_SECONDS, 30); // 30 seconds
    leakedResourceCleanUpIntervalInMS = TimeUnit.MINUTES.toMillis(serverProperties.getLong(SERVER_LEAKED_RESOURCE_CLEAN_UP_INTERVAL_IN_MINUTES, 10)); // 10 mins by default
    readOnlyForBatchOnlyStoreEnabled = serverProperties.getBoolean(SERVER_DB_READ_ONLY_FOR_BATCH_ONLY_STORE_ENABLED, true);
    quotaEnforcementEnabled = serverProperties.getBoolean(SERVER_QUOTA_ENFORCEMENT_ENABLED, false);
    //June 2018, venice-6 nodes were hitting ~20k keys per second. August 2018, no cluster has nodes above 3.5k keys per second
    nodeCapacityInRcu = serverProperties.getLong(SERVER_NODE_CAPACITY_RCU, 50000);
    kafkaMaxPollRecords = serverProperties.getInt(SERVER_KAFKA_MAX_POLL_RECORDS, 100);
    kafkaPollRetryTimes = serverProperties.getInt(SERVER_KAFKA_POLL_RETRY_TIMES, 100);
    kafkaPollRetryBackoffMs = serverProperties.getInt(SERVER_KAFKA_POLL_RETRY_BACKOFF_MS, 0);
    diskHealthCheckIntervalInMS = TimeUnit.SECONDS.toMillis(serverProperties.getLong(SERVER_DISK_HEALTH_CHECK_INTERVAL_IN_SECONDS, 10)); // 10 seconds by default
    diskHealthCheckTimeoutInMs = TimeUnit.SECONDS.toMillis(serverProperties.getLong(SERVER_DISK_HEALTH_CHECK_TIMEOUT_IN_SECONDS, 30)); // 30 seconds by default
    diskHealthCheckServiceEnabled = serverProperties.getBoolean(SERVER_DISK_HEALTH_CHECK_SERVICE_ENABLED, true);
    computeFastAvroEnabled = serverProperties.getBoolean(SERVER_COMPUTE_FAST_AVRO_ENABLED, false);
    participantMessageConsumptionDelayMs = serverProperties.getLong(PARTICIPANT_MESSAGE_CONSUMPTION_DELAY_MS, 60000);
    serverPromotionToLeaderReplicaDelayMs = TimeUnit.SECONDS.toMillis(serverProperties.getLong(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, 300));  // 5 minutes by default
    hybridQuotaEnabled = serverProperties.getBoolean(HYBRID_QUOTA_ENFORCEMENT_ENABLED, false);

    enableParallelBatchGet = serverProperties.getBoolean(SERVER_ENABLE_PARALLEL_BATCH_GET, false);
    parallelBatchGetChunkSize = serverProperties.getInt(SERVER_PARALLEL_BATCH_GET_CHUNK_SIZE, 5);

    keyValueProfilingEnabled = serverProperties.getBoolean(KEY_VALUE_PROFILING_ENABLED, false);
    enableDatabaseMemoryStats = serverProperties.getBoolean(SERVER_DATABASE_MEMORY_STATS_ENABLED, true);

    Map<String, String> storeToEarlyTerminationThresholdMSMapProp = serverProperties.getMap(
        SERVER_STORE_TO_EARLY_TERMINATION_THRESHOLD_MS_MAP, Collections.emptyMap());
    storeToEarlyTerminationThresholdMSMap = new HashMap<>();
    storeToEarlyTerminationThresholdMSMapProp.forEach( (storeName, thresholdStr) -> {
      storeToEarlyTerminationThresholdMSMap.put(storeName, Integer.parseInt(thresholdStr.trim()));
    });
    databaseLookupQueueCapacity = serverProperties.getInt(SERVER_DATABASE_LOOKUP_QUEUE_CAPACITY, Integer.MAX_VALUE);
    computeQueueCapacity = serverProperties.getInt(SERVER_COMPUTE_QUEUE_CAPACITY, Integer.MAX_VALUE);
    kafkaOpenSSLEnabled = serverProperties.getBoolean(SERVER_ENABLE_KAFKA_OPENSSL, false);
    helixOfflinePushEnabled = serverProperties.getBoolean(HELIX_OFFLINE_PUSH_ENABLED, false);
    helixHybridStoreQuotaEnabled = serverProperties.getBoolean(HELIX_HYBRID_STORE_QUOTA_ENABLED, false);
    ssdHealthCheckShutdownTimeMs = serverProperties.getLong(SERVER_SHUTDOWN_DISK_UNHEALTHY_TIME_MS, 200000);

    /**
     * {@link com.linkedin.venice.utils.queues.FairBlockingQueue} could cause non-deterministic behavior during test.
     * Disable it by default for now.
     *
     * In the test of feature store user case, when we did a rolling bounce of storage nodes, the high latency happened
     * to one or two storage nodes randomly. And when we restarted the node with high latency, the high latency could
     * disappear, but other nodes could start high latency.
     * After switching to {@link java.util.concurrent.LinkedBlockingQueue}, this issue never happened.
     *
     * TODO: figure out the issue with {@link com.linkedin.venice.utils.queues.FairBlockingQueue}.
     */
    String blockingQueueTypeStr = serverProperties.getString(SERVER_BLOCKING_QUEUE_TYPE, BlockingQueueType.LINKED_BLOCKING_QUEUE.name());
    try {
      blockingQueueType = BlockingQueueType.valueOf(blockingQueueTypeStr);
    } catch (IllegalArgumentException e) {
      throw new VeniceException("Valid blocking queue options: " + Arrays.toString(BlockingQueueType.values()));
    }

    restServiceEpollEnabled = serverProperties.getBoolean(SERVER_REST_SERVICE_EPOLL_ENABLED, false);
    kafkaAdminClass = serverProperties.getString(KAFKA_ADMIN_CLASS, ScalaAdminUtils.class.getName());
    // Disable it by default, and when router connection warming is enabled, we need to adjust this config.
    routerConnectionWarmingDelayMs = serverProperties.getLong(SERVER_ROUTER_CONNECTION_WARMING_DELAY_MS, 0);
    sharedConsumerPoolEnabled = serverProperties.getBoolean(SERVER_SHARED_CONSUMER_POOL_ENABLED, false);
    consumerPoolSizePerKafkaCluster = serverProperties.getInt(SERVER_CONSUMER_POOL_SIZE_PER_KAFKA_CLUSTER, 5);
    if (consumerPoolSizePerKafkaCluster < MINIMUM_CONSUMER_NUM_IN_CONSUMER_POOL_PER_KAFKA_CLUSTER) {
      throw new VeniceException(SERVER_CONSUMER_POOL_SIZE_PER_KAFKA_CLUSTER + " shouldn't be less than: " +
          MINIMUM_CONSUMER_NUM_IN_CONSUMER_POOL_PER_KAFKA_CLUSTER + ", but it is " + consumerPoolSizePerKafkaCluster);
    }
    leakedResourceCleanupEnabled = serverProperties.getBoolean(SERVER_LEAKED_RESOURCE_CLEANUP_ENABLED, true);
    cacheWarmingBeforeReadyToServeEnabled = serverProperties.getBoolean(SERVER_CACHE_WARMING_BEFORE_READY_TO_SERVE_ENABLED, false);
    List<String> cacheWarmingStoreList = serverProperties.getList(SERVER_CACHE_WARMING_STORE_LIST, Collections.emptyList());
    cacheWarmingStoreSet = new HashSet<>(cacheWarmingStoreList);
    cacheWarmingThreadPoolSize = serverProperties.getInt(SERVER_CACHE_WARMING_THREAD_POOL_SIZE, 4);
    delayReadyToServeMS = serverProperties.getLong(SERVER_DELAY_REPORT_READY_TO_SERVE_MS, 0); // by default no delay

    ingestionMode = IngestionMode.valueOf(serverProperties.getString(SERVER_INGESTION_MODE, IngestionMode.BUILT_IN.toString()));
    ingestionServicePort = serverProperties.getInt(SERVER_INGESTION_ISOLATION_SERVICE_PORT, 27015);
    ingestionApplicationPort = serverProperties.getInt(SERVER_INGESTION_ISOLATION_APPLICATION_PORT, 27016);
    databaseChecksumVerificationEnabled = serverProperties.getBoolean(SERVER_DATABASE_CHECKSUM_VERIFICATION_ENABLED, false);

    kafkaConsumerConfigsForLocalConsumption = serverProperties.clipAndFilterNamespace(SERVER_LOCAL_CONSUMER_CONFIG_PREFIX);
    kafkaConsumerConfigsForRemoteConsumption = serverProperties.clipAndFilterNamespace(SERVER_REMOTE_CONSUMER_CONFIG_PREFIX);

    rocksDbStorageEngineConfigCheckEnabled = serverProperties.getBoolean(SERVER_ROCKSDB_STORAGE_CONFIG_CHECK_ENABLED, true);

    freezeIngestionIfReadyToServeOrLocalDataExists = serverProperties.getBoolean(FREEZE_INGESTION_IF_READY_TO_SERVE_OR_LOCAL_DATA_EXISTS, false);

    systemSchemaClusterName = serverProperties.getString(SYSTEM_SCHEMA_CLUSTER_NAME, "");
    sharedConsumerNonExistingTopicCleanupDelayMS = serverProperties.getLong(SERVER_SHARED_CONSUMER_NON_EXISTING_TOPIC_CLEANUP_DELAY_MS, TimeUnit.MINUTES.toMillis(10)); // default 10 mins
  }

  public int getListenerPort() {
    return listenerPort;
  }


  /**
   * Get base path of Venice storage data.
   *
   * @return Base path of persisted Venice database files.
   */
  public String getDataBasePath() {
    return this.dataBasePath;
  }

  public boolean isAutoCreateDataPath(){
    return autoCreateDataPath;
  }

  public RocksDBServerConfig getRocksDBServerConfig() {
    return rocksDBServerConfig;
  }

  public boolean isServerWhitelistEnabled() {
    return enableServerWhiteList;
  }

  public int getMaxOnlineOfflineStateTransitionThreadNumber() {
    return maxOnlineOfflineStateTransitionThreadNumber;
  }

  public int getMaxLeaderFollowerStateTransitionThreadNumber() {
    return maxLeaderFollowerStateTransitionThreadNumber;
  }

  public int getStoreWriterNumber() {
    return this.storeWriterNumber;
  }

  public long getStoreWriterBufferMemoryCapacity() {
    return this.storeWriterBufferMemoryCapacity;
  }

  public long getStoreWriterBufferNotifyDelta() {
    return this.storeWriterBufferNotifyDelta;
  }

  public int getRestServiceStorageThreadNum() {
    return restServiceStorageThreadNum;
  }

  public int getServerComputeThreadNum() {
    return serverComputeThreadNum;
  }

  public int getNettyIdleTimeInSeconds() {
    return nettyIdleTimeInSeconds;
  }

  public int getMaxRequestSize() {
    return maxRequestSize;
  }

  public int getTopicOffsetCheckIntervalMs() {
    return topicOffsetCheckIntervalMs;
  }

  public int getNettyGracefulShutdownPeriodSeconds() {
    return nettyGracefulShutdownPeriodSeconds;
  }

  public int getNettyWorkerThreadCount() {
    return nettyWorkerThreadCount;
  }

  public long getDatabaseSyncBytesIntervalForTransactionalMode() {
    return databaseSyncBytesIntervalForTransactionalMode;
  }

  public long getDatabaseSyncBytesIntervalForDeferredWriteMode() {
    return databaseSyncBytesIntervalForDeferredWriteMode;
  }

  public double getDiskFullThreshold(){
    return diskFullThreshold;
  }

  public int getPartitionGracefulDropDelaySeconds() {
    return partitionGracefulDropDelaySeconds;
  }

  public long getLeakedResourceCleanUpIntervalInMS() {
    return leakedResourceCleanUpIntervalInMS;
  }

  public boolean isReadOnlyForBatchOnlyStoreEnabled() {
    return readOnlyForBatchOnlyStoreEnabled;
  }

  public boolean isQuotaEnforcementEnabled() {
    return quotaEnforcementEnabled;
  }

  public long getNodeCapacityInRcu(){
    return nodeCapacityInRcu;
  }

  public int getKafkaMaxPollRecords() {
    return kafkaMaxPollRecords;
  }

  public int getKafkaPollRetryTimes() {
    return kafkaPollRetryTimes;
  }

  public int getKafkaPollRetryBackoffMs() {
    return kafkaPollRetryBackoffMs;
  }

  public long getDiskHealthCheckIntervalInMS() {
    return diskHealthCheckIntervalInMS;
  }

  public long getDiskHealthCheckTimeoutInMs() {
    return diskHealthCheckTimeoutInMs;
  }

  public boolean isDiskHealthCheckServiceEnabled() {
    return diskHealthCheckServiceEnabled;
  }

  public BlockingQueue<Runnable> getExecutionQueue(int capacity) {
    switch (blockingQueueType) {
      case FAIR_BLOCKING_QUEUE:
        /**
         * Currently, {@link FairBlockingQueue} doesn't support capacity control.
         * TODO: add capacity support to {@link FairBlockingQueue}.
         */
        return new FairBlockingQueue<>();
      case LINKED_BLOCKING_QUEUE:
        return new LinkedBlockingQueue<>(capacity);
      case ARRAY_BLOCKING_QUEUE:
        if (capacity == Integer.MAX_VALUE) {
          throw new VeniceException("Queue capacity must be specified when using " + ARRAY_BLOCKING_QUEUE);
        }
        return new ArrayBlockingQueue<>(capacity);
      default:
        throw new VeniceException("Unknown blocking queue type: " + blockingQueueType);
    }
  }

  public boolean isComputeFastAvroEnabled() {
    return computeFastAvroEnabled;
  }

  public long getParticipantMessageConsumptionDelayMs() { return participantMessageConsumptionDelayMs; }

  public long getServerPromotionToLeaderReplicaDelayMs() {
    return serverPromotionToLeaderReplicaDelayMs;
  }

  public boolean isHybridQuotaEnabled() { return hybridQuotaEnabled; }

  public boolean isEnableParallelBatchGet() {
    return enableParallelBatchGet;
  }

  public int getParallelBatchGetChunkSize() {
    return parallelBatchGetChunkSize;
  }

  public boolean isKeyValueProfilingEnabled() {
    return keyValueProfilingEnabled;
  }

  public boolean isDatabaseMemoryStatsEnabled() {
    return enableDatabaseMemoryStats;
  }

  public Map<String, Integer> getStoreToEarlyTerminationThresholdMSMap() {
    return storeToEarlyTerminationThresholdMSMap;
  }

  public int getDatabaseLookupQueueCapacity() {
    return databaseLookupQueueCapacity;
  }

  public int getComputeQueueCapacity() {
    return computeQueueCapacity;
  }

  public boolean isRestServiceEpollEnabled() {
    return restServiceEpollEnabled;
  }

  public String getKafkaAdminClass() {
    return kafkaAdminClass;
  }

  public boolean isKafkaOpenSSLEnabled() {
    return kafkaOpenSSLEnabled;
  }

  public long getRouterConnectionWarmingDelayMs() {
    return routerConnectionWarmingDelayMs;
  }

  public boolean isHelixOfflinePushEnabled() {
    return helixOfflinePushEnabled;
  }
  public boolean isHelixHybridStoreQuotaEnabled() {
    return helixHybridStoreQuotaEnabled;
  }

  public long getSsdHealthCheckShutdownTimeMs() {
    return ssdHealthCheckShutdownTimeMs;
  }

  public boolean isSharedConsumerPoolEnabled() {
    return sharedConsumerPoolEnabled;
  }

  public int getConsumerPoolSizePerKafkaCluster() {
    return consumerPoolSizePerKafkaCluster;
  }

  public boolean isLeakedResourceCleanupEnabled() {
    return leakedResourceCleanupEnabled;
  }

  public boolean isCacheWarmingBeforeReadyToServeEnabled() {
    return cacheWarmingBeforeReadyToServeEnabled;
  }

  public boolean isCacheWarmingEnabledForStore(String storeName) {
    return cacheWarmingStoreSet.contains(storeName);
  }

  public int getCacheWarmingThreadPoolSize() {
    return cacheWarmingThreadPoolSize;
  }

  public long getDelayReadyToServeMS() {
    return delayReadyToServeMS;
  }

  public IngestionMode getIngestionMode() {
    return ingestionMode;
  }

  public int getIngestionServicePort() {
    return ingestionServicePort;
  }

  public int getIngestionApplicationPort() {
    return ingestionApplicationPort;
  }

  public boolean isDatabaseChecksumVerificationEnabled() { return databaseChecksumVerificationEnabled;}

  public VeniceProperties getKafkaConsumerConfigsForLocalConsumption() {
    return kafkaConsumerConfigsForLocalConsumption;
  }

  public VeniceProperties getKafkaConsumerConfigsForRemoteConsumption() {
    return kafkaConsumerConfigsForRemoteConsumption;
  }

  public boolean isRocksDbStorageEngineConfigCheckEnabled() {
    return rocksDbStorageEngineConfigCheckEnabled;
  }

  public boolean freezeIngestionIfReadyToServeOrLocalDataExists() {
    return freezeIngestionIfReadyToServeOrLocalDataExists;
  }

  public String getSystemSchemaClusterName() {
    return systemSchemaClusterName;
  }

  public long getSharedConsumerNonExistingTopicCleanupDelayMS() {
    return sharedConsumerNonExistingTopicCleanupDelayMS;
  }
}