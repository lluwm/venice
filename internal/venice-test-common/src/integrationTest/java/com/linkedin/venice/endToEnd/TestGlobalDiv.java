package com.linkedin.venice.endToEnd;

import static com.linkedin.venice.ConfigKeys.*;
import static com.linkedin.venice.integration.utils.VeniceClusterWrapper.*;
import static com.linkedin.venice.utils.TestWriteUtils.*;
import static com.linkedin.venice.utils.Utils.*;

import com.linkedin.davinci.kafka.consumer.KafkaConsumerService;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.integration.utils.PubSubBrokerWrapper;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.meta.PersistenceType;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.pubsub.PubSubProducerAdapterFactory;
import com.linkedin.venice.serializer.AvroSerializer;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.writer.VeniceWriter;
import com.linkedin.venice.writer.VeniceWriterOptions;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Properties;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


public class TestGlobalDiv {
  private static final Logger LOGGER = LogManager.getLogger(TestGlobalDiv.class);

  private VeniceClusterWrapper sharedVenice;

  @BeforeClass
  public void setUp() {
    Properties extraProperties = new Properties();
    extraProperties.setProperty(PERSISTENCE_TYPE, PersistenceType.ROCKS_DB.name());
    extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(1L));

    // N.B.: RF 2 with 3 servers is important, in order to test both the leader and follower code paths
    sharedVenice = ServiceFactory.getVeniceCluster(1, 0, 0, 2, 1000000, false, false, extraProperties);

    Properties routerProperties = new Properties();

    sharedVenice.addVeniceRouter(routerProperties);
    // Added a server with shared consumer enabled.
    Properties serverPropertiesWithSharedConsumer = new Properties();
    serverPropertiesWithSharedConsumer.setProperty(SSL_TO_KAFKA_LEGACY, "false");
    extraProperties.setProperty(SERVER_CONSUMER_POOL_SIZE_PER_KAFKA_CLUSTER, "3");
    extraProperties.setProperty(DEFAULT_MAX_NUMBER_OF_PARTITIONS, "4");
    extraProperties.setProperty(
        SERVER_SHARED_CONSUMER_ASSIGNMENT_STRATEGY,
        KafkaConsumerService.ConsumerAssignmentStrategy.PARTITION_WISE_SHARED_CONSUMER_ASSIGNMENT_STRATEGY.name());
    sharedVenice.addVeniceServer(serverPropertiesWithSharedConsumer, extraProperties);
    sharedVenice.addVeniceServer(serverPropertiesWithSharedConsumer, extraProperties);
    sharedVenice.addVeniceServer(serverPropertiesWithSharedConsumer, extraProperties);
    LOGGER.info("Finished creating VeniceClusterWrapper");
  }

  @AfterClass
  public void cleanUp() {
    Utils.closeQuietlyWithErrorLogged(sharedVenice);
  }

  @Test(timeOut = 180 * Time.MS_PER_SECOND)
  public void testChunkedDiv() {
    String storeName = Utils.getUniqueString("store");
    final int partitionCount = 1;
    final int keyCount = 10;

    UpdateStoreQueryParams params = new UpdateStoreQueryParams()
        // set hybridRewindSecond to a big number so following versions won't ignore old records in RT
        .setHybridRewindSeconds(2000000)
        .setHybridOffsetLagThreshold(10)
        .setPartitionCount(partitionCount);

    sharedVenice.useControllerClient(client -> {
      client.createNewStore(storeName, "owner", DEFAULT_KEY_SCHEMA, DEFAULT_VALUE_SCHEMA);
      client.updateStore(storeName, params);
    });

    // Create store version 1 by writing keyCount records.
    sharedVenice.createVersion(
        storeName,
        DEFAULT_KEY_SCHEMA,
        DEFAULT_VALUE_SCHEMA,
        IntStream.range(0, keyCount).mapToObj(i -> new AbstractMap.SimpleEntry<>(i, i)));

    Properties veniceWriterProperties = new Properties();
    veniceWriterProperties.put(KAFKA_BOOTSTRAP_SERVERS, sharedVenice.getPubSubBrokerWrapper().getAddress());

    // Set max segment elapsed time to 0 to enforce creating small segments aggressively
    veniceWriterProperties.put(VeniceWriter.MAX_ELAPSED_TIME_FOR_SEGMENT_IN_MS, "0");
    veniceWriterProperties.putAll(
        PubSubBrokerWrapper
            .getBrokerDetailsForClients(Collections.singletonList(sharedVenice.getPubSubBrokerWrapper())));
    PubSubProducerAdapterFactory pubSubProducerAdapterFactory =
        sharedVenice.getPubSubBrokerWrapper().getPubSubClientsFactory().getProducerAdapterFactory();
    AvroSerializer<String> stringSerializer = new AvroSerializer(STRING_SCHEMA);

    try (VeniceWriter<byte[], byte[], byte[]> realTimeTopicWriter =
        TestUtils.getVeniceWriterFactory(veniceWriterProperties, pubSubProducerAdapterFactory)
            .createVeniceWriter(new VeniceWriterOptions.Builder(Version.composeRealTimeTopic(storeName)).build())) {
      LOGGER.info("llu: Sending chunk supported div message");
      realTimeTopicWriter.sendChunkSupportedDivMessage(
          0,
          stringSerializer.serialize(String.valueOf(100)),
          stringSerializer.serialize(String.valueOf(100)));
      sleep(10000);
    }
  }
}
