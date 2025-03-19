package com.linkedin.venice.client.stats;

import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_CLUSTER_NAME;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_REQUEST_METHOD;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_RESPONSE_STATUS_CODE_CATEGORY;
import static com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions.VENICE_STORE_NAME;
import static com.linkedin.venice.utils.CollectionUtils.setOf;

import com.linkedin.venice.stats.dimensions.VeniceMetricsDimensions;
import com.linkedin.venice.stats.metrics.MetricEntity;
import com.linkedin.venice.stats.metrics.MetricType;
import com.linkedin.venice.stats.metrics.MetricUnit;
import java.util.Set;


public enum BasicClientMetricEntity {
  /**
   * Count of all requests during response handling along with response codes
   */
  CALL_COUNT(
      MetricType.COUNTER, MetricUnit.NUMBER, "Count of all requests during response handling along with response codes",
      setOf(VENICE_STORE_NAME, VENICE_CLUSTER_NAME, VENICE_REQUEST_METHOD, VENICE_RESPONSE_STATUS_CODE_CATEGORY)
  );

  private final MetricEntity metricEntity;

  BasicClientMetricEntity(
      MetricType metricType,
      MetricUnit unit,
      String description,
      Set<VeniceMetricsDimensions> dimensions) {
    this.metricEntity = new MetricEntity(this.name().toLowerCase(), metricType, unit, description, dimensions);
  }

  public MetricEntity getMetricEntity() {
    return metricEntity;
  }
}
