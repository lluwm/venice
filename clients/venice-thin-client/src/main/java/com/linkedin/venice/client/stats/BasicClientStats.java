package com.linkedin.venice.client.stats;

import static com.linkedin.venice.client.stats.BasicClientMetricEntity.CALL_COUNT;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_CLUSTER_NAME;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_REQUEST_METHOD;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_STORE_NAME;

import com.google.common.collect.ImmutableMap;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.read.RequestType;
import com.linkedin.venice.stats.AbstractVeniceHttpStats;
import com.linkedin.venice.stats.TehutiUtils;
import com.linkedin.venice.stats.VeniceMetricsRepository;
import com.linkedin.venice.stats.VeniceOpenTelemetryMetricsRepository;
import com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions;
import com.linkedin.venice.stats.dimensions.VeniceResponseStatusCategory;
import com.linkedin.venice.stats.metrics.MetricEntityStateOneEnum;
import com.linkedin.venice.stats.metrics.TehutiMetricNameEnum;
import io.tehuti.metrics.MetricsRepository;
import io.tehuti.metrics.Sensor;
import io.tehuti.metrics.stats.Avg;
import io.tehuti.metrics.stats.Max;
import io.tehuti.metrics.stats.OccurrenceRate;
import io.tehuti.metrics.stats.Rate;
import java.util.Collections;
import java.util.Map;


/**
 * This class offers very basic metrics for client, and right now, it is directly used by DaVinci.
 */
public class BasicClientStats extends AbstractVeniceHttpStats {
  private static final String SYSTEM_STORE_NAME_PREFIX = "venice_system_store_";

  private static final MetricsRepository dummySystemStoreMetricRepo = new MetricsRepository();
  private MetricEntityStateOneEnum<VeniceResponseStatusCategory> healthyReqMetric;
  private MetricEntityStateOneEnum<VeniceResponseStatusCategory> unhealthyReqMetric;
  private volatile Map<VeniceMetricsDimensions, String> commonDims;
  private final Sensor requestSensor;
  private final Sensor healthySensor;
  private final Sensor unhealthySensor;
  private final Sensor healthyRequestLatencySensor;
  private final Sensor requestKeyCountSensor;
  private final Sensor successRequestKeyCountSensor;
  private final Sensor successRequestRatioSensor;
  private final Sensor successRequestKeyRatioSensor;
  private final Rate requestRate = new OccurrenceRate();
  private final Rate successRequestKeyCountRate = new Rate();

  public static BasicClientStats getClientStats(
      MetricsRepository metricsRepository,
      String storeName,
      RequestType requestType,
      ClientConfig clientConfig) {
    String prefix = clientConfig == null ? null : clientConfig.getStatsPrefix();
    String metricName = prefix == null || prefix.isEmpty() ? storeName : prefix + "." + storeName;
    return new BasicClientStats(metricsRepository, metricName, requestType);
  }

  protected BasicClientStats(MetricsRepository metricsRepository, String storeName, RequestType requestType) {
    super(
        storeName.startsWith(SYSTEM_STORE_NAME_PREFIX) ? dummySystemStoreMetricRepo : metricsRepository,
        storeName,
        requestType);
    requestSensor = registerSensor("request", requestRate);
    Rate healthyRequestRate = new OccurrenceRate();
    healthySensor = registerSensor("healthy_request", healthyRequestRate);
    unhealthySensor = registerSensor("unhealthy_request", new OccurrenceRate());
    healthyRequestLatencySensor = registerSensorWithDetailedPercentiles("healthy_request_latency", new Avg());
    successRequestRatioSensor =
        registerSensor(new TehutiUtils.SimpleRatioStat(healthyRequestRate, requestRate, "success_request_ratio"));
    Rate requestKeyCountRate = new Rate();
    requestKeyCountSensor = registerSensor("request_key_count", requestKeyCountRate, new Avg(), new Max());
    successRequestKeyCountSensor =
        registerSensor("success_request_key_count", successRequestKeyCountRate, new Avg(), new Max());

    successRequestKeyRatioSensor = registerSensor(
        new TehutiUtils.SimpleRatioStat(successRequestKeyCountRate, requestKeyCountRate, "success_request_key_ratio"));
  }

  public void registerOTelMetrics(String cluster, String storeName, RequestType requestType) {
    VeniceOpenTelemetryMetricsRepository oTelRepo = null;
    MetricsRepository metricsRepo = getMetricsRepository();
    if (metricsRepo instanceof VeniceMetricsRepository) {
      VeniceMetricsRepository veniceMetricsRepo = (VeniceMetricsRepository) metricsRepo;
      oTelRepo = veniceMetricsRepo.getOpenTelemetryMetricsRepository();
      boolean emitOTelMetrics = veniceMetricsRepo.getVeniceMetricsConfig().emitOtelMetrics();
      if (emitOTelMetrics) {
        // Set up common dimensions once for all metrics.
        commonDims = ImmutableMap.of(
            VENICE_STORE_NAME,
            storeName,
            VENICE_REQUEST_METHOD,
            requestType.toString(),
            VENICE_CLUSTER_NAME,
            cluster);
      }
    }

    healthyReqMetric = MetricEntityStateOneEnum.create(
        CALL_COUNT.getMetricEntity(),
        oTelRepo,
        this::registerSensor,
        BasicClientTehutiMetricName.HEALTHY_REQUEST,
        Collections.singletonList(new OccurrenceRate()),
        commonDims,
        VeniceResponseStatusCategory.class);

    unhealthyReqMetric = MetricEntityStateOneEnum.create(
        CALL_COUNT.getMetricEntity(),
        oTelRepo,
        this::registerSensor,
        BasicClientTehutiMetricName.UNHEALTHY_REQUEST,
        Collections.singletonList(new OccurrenceRate()),
        commonDims,
        VeniceResponseStatusCategory.class);
  }

  private void recordRequest() {
    requestSensor.record();
  }

  public void recordHealthyRequest() {
    recordRequest();
    healthyReqMetric.record(1, VeniceResponseStatusCategory.SUCCESS);
  }

  public void recordUnhealthyRequest() {
    recordRequest();
    unhealthyReqMetric.record(1, VeniceResponseStatusCategory.FAIL);
  }

  public void recordHealthyLatency(double latency) {
    healthyRequestLatencySensor.record(latency);
  }

  public void recordRequestKeyCount(int keyCount) {
    requestKeyCountSensor.record(keyCount);
  }

  public void recordSuccessRequestKeyCount(int successKeyCount) {
    successRequestKeyCountSensor.record(successKeyCount);
  }

  protected final Rate getRequestRate() {
    return requestRate;
  }

  protected final Rate getSuccessRequestKeyCountRate() {
    return successRequestKeyCountRate;
  }

  // For testing only.
  public Map<VeniceMetricsDimensions, String> getCommonDims() {
    return commonDims;
  }

  /**
   * Metric names for tehuti metrics used in this class
   */
  enum BasicClientTehutiMetricName implements TehutiMetricNameEnum {
    /** for {@link BasicClientMetricEntity#CALL_COUNT} */
    HEALTHY_REQUEST, UNHEALTHY_REQUEST;

    private final String metricName;

    BasicClientTehutiMetricName() {
      this.metricName = name().toLowerCase();
    }

    @Override
    public String getMetricName() {
      return this.metricName;
    }
  }
}
