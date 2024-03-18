package com.linkedin.venice.endToEnd;

import static com.linkedin.davinci.store.rocksdb.RocksDBServerConfig.*;
import static com.linkedin.venice.ConfigKeys.*;
import static com.linkedin.venice.utils.IntegrationTestPushUtils.*;
import static com.linkedin.venice.utils.TestWriteUtils.*;
import static org.testng.Assert.*;

import com.linkedin.davinci.kafka.consumer.KafkaConsumerService;
import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.controllerapi.VersionCreationResponse;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceControllerCreateOptions;
import com.linkedin.venice.integration.utils.VeniceControllerWrapper;
import com.linkedin.venice.integration.utils.ZkServerWrapper;
import com.linkedin.venice.meta.PersistenceType;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.samza.system.SystemProducer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


public class TestSegmentWarnings {
  private static final Logger LOGGER = LogManager.getLogger(TestHybridStoreDeletion.class);
  public static final int STREAMING_RECORD_SIZE = 1024;
  public static final int NUMBER_OF_SERVERS = 1;

  private VeniceClusterWrapper veniceCluster;
  ZkServerWrapper parentZk = null;
  VeniceControllerWrapper parentController = null;

  @BeforeClass(alwaysRun = true)
  public void setUp() {
    veniceCluster = setUpCluster();
  }

  @AfterClass(alwaysRun = true)
  public void cleanUp() {
    parentController.close();
    parentZk.close();
    Utils.closeQuietlyWithErrorLogged(veniceCluster);
  }

  private static VeniceClusterWrapper setUpCluster() {
    Properties extraProperties = new Properties();
    extraProperties.setProperty(DEFAULT_MAX_NUMBER_OF_PARTITIONS, "5");
    VeniceClusterWrapper cluster = ServiceFactory.getVeniceCluster(1, 0, 1, 1, 1000000, false, false, extraProperties);

    // Add Venice Router
    Properties routerProperties = new Properties();
    cluster.addVeniceRouter(routerProperties);

    // Add Venice Server
    Properties serverProperties = new Properties();
    serverProperties.setProperty(PERSISTENCE_TYPE, PersistenceType.ROCKS_DB.name());
    serverProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(1L));
    serverProperties.setProperty(ROCKSDB_PLAIN_TABLE_FORMAT_ENABLED, "false");
    serverProperties.setProperty(SERVER_DATABASE_CHECKSUM_VERIFICATION_ENABLED, "true");
    serverProperties.setProperty(SERVER_DATABASE_SYNC_BYTES_INTERNAL_FOR_DEFERRED_WRITE_MODE, "300");

    serverProperties.setProperty(SSL_TO_KAFKA_LEGACY, "false");

    // Limit shared consumer thread pool size to 1.
    serverProperties.setProperty(SERVER_CONSUMER_POOL_SIZE_PER_KAFKA_CLUSTER, "2");
    serverProperties.setProperty(MIN_CONSUMER_IN_CONSUMER_POOL_PER_KAFKA_CLUSTER, "2");
    serverProperties.setProperty(SERVER_DEDICATED_DRAINER_FOR_SORTED_INPUT_ENABLED, "true");

    // Set the max number of partitions to 1 so that we can test the partition-wise shared consumer assignment strategy.
    serverProperties.setProperty(DEFAULT_MAX_NUMBER_OF_PARTITIONS, "1");
    serverProperties.setProperty(
        SERVER_SHARED_CONSUMER_ASSIGNMENT_STRATEGY,
        KafkaConsumerService.ConsumerAssignmentStrategy.PARTITION_WISE_SHARED_CONSUMER_ASSIGNMENT_STRATEGY.name());

    // Set the database sync bytes internal to a small value so that we can test the deferred write mode.
    serverProperties.setProperty(SERVER_DATABASE_SYNC_BYTES_INTERNAL_FOR_DEFERRED_WRITE_MODE, "100");
    serverProperties.setProperty(SERVER_DATABASE_SYNC_BYTES_INTERNAL_FOR_TRANSACTIONAL_MODE, "100");
    serverProperties.setProperty(DIV_PRODUCER_STATE_MAX_AGE_MS, "1000");

    for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
      cluster.addVeniceServer(new Properties(), serverProperties);
    }

    return cluster;
  }

  /**
   * testHybridStoreRTDeletionWhileIngesting does the following:
   *
   * 1. Set up a Venice cluster with 1 controller, 1 router, and 1 server.
   * 2. Limit the shared consumer thread pool size to 1 on the server.
   * 3. Create two hybrid stores.
   * 4. Produce to the rt topic of the first store and allow the thread to produce some amount of data.
   * 5. Delete the rt topic of the first store and wait for the rt topic to be fully deleted.
   * 6. Produce to the rt topic of the second store with 10 key-value pairs.
   * 7. Check that the second store has all the records.
   */
  @Test(timeOut = 120 * Time.MS_PER_SECOND)
  public void testHybridStoreRTDeletionWhileIngesting() {
    parentZk = ServiceFactory.getZkServer();
    parentController = ServiceFactory.getVeniceController(
        new VeniceControllerCreateOptions.Builder(
            veniceCluster.getClusterName(),
            parentZk,
            veniceCluster.getPubSubBrokerWrapper())
                .childControllers(new VeniceControllerWrapper[] { veniceCluster.getLeaderVeniceController() })
                .build());

    long streamingRewindSeconds = 60;
    long streamingMessageLag = 2;
    final String storeName = Utils.getUniqueString("hybrid-store-test");
    final String[] storeNames = new String[] { storeName };

    try (
        ControllerClient controllerClient =
            new ControllerClient(veniceCluster.getClusterName(), parentController.getControllerUrl());
        AvroGenericStoreClient<Object, Object> clientToStore = ClientFactory.getAndStartGenericAvroClient(
            ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(veniceCluster.getRandomRouterURL()))) {
      createStoresAndVersions(controllerClient, storeNames, streamingRewindSeconds, streamingMessageLag);

      // Produce to the rt topic of the first store and allow the thread to produce some amount of data.
      produceToStoreRTTopic(storeName, 20);

      // Check that the second store has all the records.
      TestUtils.waitForNonDeterministicAssertion(20, TimeUnit.SECONDS, true, true, () -> {
        try {
          for (int i = 1; i <= 10; i++) {
            checkLargeRecord(clientToStore, i);
            LOGGER.info("Checked record {}", i);
          }
        } catch (Exception e) {
          throw new VeniceException(e);
        }
      });
      // sleep 20 seconds.
      Thread.sleep(20 * Time.MS_PER_SECOND);

      // Create a new version, and do an empty push for that version.
      controllerClient.emptyPush(storeName, Utils.getUniqueString("empty-hybrid-push"), 1L);

      Thread.sleep(20 * Time.MS_PER_SECOND);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private void produceToStoreRTTopic(String storeName, int numOfRecords) {
    SystemProducer veniceProducer = getSamzaProducer(veniceCluster, storeName, Version.PushType.STREAM);
    for (int i = 1; i <= numOfRecords; i++) {
      sendCustomSizeStreamingRecord(veniceProducer, storeName, i, STREAMING_RECORD_SIZE);
    }
    veniceProducer.stop();
  }

  private void checkLargeRecord(AvroGenericStoreClient client, int index)
      throws ExecutionException, InterruptedException {
    String key = Integer.toString(index);
    assert client != null;
    Object obj = client.get(key).get();
    String value = obj.toString();
    assertEquals(
        value.length(),
        STREAMING_RECORD_SIZE,
        "Expected a large record for key '" + key + "' but instead got: '" + value + "'.");

    String expectedChar = Integer.toString(index).substring(0, 1);
    for (int i = 0; i < value.length(); i++) {
      assertEquals(value.substring(i, i + 1), expectedChar);
    }
  }

  private void createStoresAndVersions(
      ControllerClient controllerClient,
      String[] storeNames,
      long streamingRewindSeconds,
      long streamingMessageLag) {
    for (String storeName: storeNames) {
      // Create store at parent, make it a hybrid store.
      controllerClient.createNewStore(storeName, "owner", STRING_SCHEMA.toString(), STRING_SCHEMA.toString());
      controllerClient.updateStore(
          storeName,
          new UpdateStoreQueryParams().setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA)
              .setHybridRewindSeconds(streamingRewindSeconds)
              .setPartitionCount(1)
              .setHybridOffsetLagThreshold(streamingMessageLag));

      // There should be no version on the store yet. f
      assertEquals(
          controllerClient.getStore(storeName).getStore().getCurrentVersion(),
          0,
          "The newly created store must have a current version of 0");

      // Create a new version, and do an empty push for that version.
      VersionCreationResponse vcr =
          controllerClient.emptyPush(storeName, Utils.getUniqueString("empty-hybrid-push"), 1L);
      int versionNumber = vcr.getVersion();
      assertNotEquals(versionNumber, 0, "requesting a topic for a push should provide a non zero version number");
    }
  }
}
