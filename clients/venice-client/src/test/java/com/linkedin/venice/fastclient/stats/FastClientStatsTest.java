package com.linkedin.venice.fastclient.stats;

import static com.linkedin.venice.client.stats.BasicClientStats.BasicClientTehutiMetricName.HEALTHY_REQUEST;
import static com.linkedin.venice.client.stats.BasicClientStats.BasicClientTehutiMetricName.UNHEALTHY_REQUEST;
import static com.linkedin.venice.fastclient.stats.FastClientMetricEntity.CALL_COUNT;
import static com.linkedin.venice.stats.VeniceOpenTelemetryMetricNamingFormat.PASCAL_CASE;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_CLUSTER_NAME;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_REQUEST_METHOD;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_STORE_NAME;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.venice.read.RequestType;
import com.linkedin.venice.stats.VeniceOpenTelemetryMetricNamingFormat;
import com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions;
import com.linkedin.venice.stats.metrics.MetricEntity;
import com.linkedin.venice.tehuti.MockTehutiReporter;
import com.linkedin.venice.utils.DataProviderUtils;
import com.linkedin.venice.utils.metrics.MetricsRepositoryUtils;
import io.netty.util.internal.StringUtil;
import io.tehuti.metrics.MetricsRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.testng.annotations.Test;


public class FastClientStatsTest {
  @Test(dataProvider = "Two-True-and-False", dataProviderClass = DataProviderUtils.class)
  public void fastClientStatsTests(boolean useVeniceMetricRepository, boolean isOtelEnabled) {
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

    FastClientStats fastCliStats =
        FastClientStats.getClientStats(metricsRepository, StringUtil.EMPTY_STRING, storeName, RequestType.SINGLE_GET);
    fastCliStats.registerOTelMetrics(clusterName, storeName, RequestType.SINGLE_GET);

    Map<VeniceMetricsDimensions, String> baseDimensionsMap = fastCliStats.getCommonDims();
    if (useVeniceMetricRepository && isOtelEnabled) {
      assertTrue(baseDimensionsMap.containsKey(VENICE_STORE_NAME));
      assertTrue(baseDimensionsMap.containsKey(VENICE_REQUEST_METHOD));
      assertTrue(baseDimensionsMap.containsKey(VENICE_CLUSTER_NAME));
      assertEquals(baseDimensionsMap.size(), 3);
    } else {
      assertNull(baseDimensionsMap);
    }

    fastCliStats.recordHealthyRequest();
    fastCliStats.recordUnhealthyRequest();
    assertTrue(
        metricsRepository.getMetric("." + storeName + "--" + HEALTHY_REQUEST.getMetricName() + ".OccurrenceRate")
            .value() > 0);
    assertTrue(
        metricsRepository.getMetric("." + storeName + "--" + UNHEALTHY_REQUEST.getMetricName() + ".OccurrenceRate")
            .value() > 0);
  }
}
