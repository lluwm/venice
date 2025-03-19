package com.linkedin.venice.client.stats;

import static com.linkedin.venice.client.stats.BasicClientMetricEntity.*;
import static com.linkedin.venice.client.stats.BasicClientStats.BasicClientTehutiMetricName.*;
import static com.linkedin.venice.stats.VeniceOpenTelemetryMetricNamingFormat.PASCAL_CASE;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_CLUSTER_NAME;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_REQUEST_METHOD;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_STORE_NAME;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.read.RequestType;
import com.linkedin.venice.stats.VeniceOpenTelemetryMetricNamingFormat;
import com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions;
import com.linkedin.venice.stats.metrics.MetricEntity;
import com.linkedin.venice.tehuti.MockTehutiReporter;
import com.linkedin.venice.utils.DataProviderUtils;
import com.linkedin.venice.utils.metrics.MetricsRepositoryUtils;
import io.tehuti.metrics.MetricsRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.testng.annotations.Test;


public class BasicClientStatsTest {
  @Test
  public void testMetricPrefix() {
    String storeName = "test_store";
    MetricsRepository metricsRepository1 = new MetricsRepository();
    // Without prefix
    ClientConfig config1 = new ClientConfig(storeName);
    BasicClientStats.getClientStats(metricsRepository1, storeName, RequestType.SINGLE_GET, config1);
    // Check metric name
    assertTrue(metricsRepository1.metrics().size() > 0);
    String metricPrefix1 = "." + storeName;
    metricsRepository1.metrics().forEach((k, v) -> {
      assertTrue(k.startsWith(metricPrefix1));
    });

    // With prefix
    String prefix = "test_prefix";
    MetricsRepository metricsRepository2 = new MetricsRepository();
    ClientConfig config2 = new ClientConfig(storeName).setStatsPrefix(prefix);
    BasicClientStats.getClientStats(metricsRepository2, storeName, RequestType.SINGLE_GET, config2);
    // Check metric name
    assertTrue(metricsRepository2.metrics().size() > 0);
    String metricPrefix2 = "." + prefix + "_" + storeName;
    metricsRepository2.metrics().forEach((k, v) -> {
      assertTrue(k.startsWith(metricPrefix2));
    });
  }

  @Test(dataProvider = "Two-True-and-False", dataProviderClass = DataProviderUtils.class)
  public void BasicClientStatsTests(boolean useVeniceMetricRepository, boolean isOtelEnabled) {
    String storeName = "test-store";
    String clusterName = "test-cluster";
    MetricsRepository metricsRepository;
    if (useVeniceMetricRepository) {
      Collection<MetricEntity> metricEntities = new ArrayList<>();
      metricEntities.add(CALL_COUNT.getMetricEntity());
      metricsRepository = MetricsRepositoryUtils.createSingleThreadedVeniceMetricsRepository(
          isOtelEnabled,
          isOtelEnabled ? PASCAL_CASE : VeniceOpenTelemetryMetricNamingFormat.getDefaultFormat(),
          metricEntities);
    } else {
      metricsRepository = MetricsRepositoryUtils.createSingleThreadedMetricsRepository();
    }
    metricsRepository.addReporter(new MockTehutiReporter());

    BasicClientStats baseCliStats = new BasicClientStats(metricsRepository, storeName, RequestType.SINGLE_GET);
    baseCliStats.registerOTelMetrics(clusterName, storeName, RequestType.SINGLE_GET);

    Map<VeniceMetricsDimensions, String> baseDimensionsMap = baseCliStats.getCommonDims();
    if (useVeniceMetricRepository && isOtelEnabled) {
      assertTrue(baseDimensionsMap.containsKey(VENICE_STORE_NAME));
      assertTrue(baseDimensionsMap.containsKey(VENICE_REQUEST_METHOD));
      assertTrue(baseDimensionsMap.containsKey(VENICE_CLUSTER_NAME));
      assertEquals(baseDimensionsMap.size(), 3);
    } else {
      assertNull(baseDimensionsMap);
    }

    baseCliStats.recordHealthyRequest();
    baseCliStats.recordUnhealthyRequest();
    assertTrue(
        metricsRepository.getMetric("." + storeName + "--" + HEALTHY_REQUEST.getMetricName() + ".OccurrenceRate")
            .value() > 0);
    assertTrue(
        metricsRepository.getMetric("." + storeName + "--" + UNHEALTHY_REQUEST.getMetricName() + ".OccurrenceRate")
            .value() > 0);
  }
}
