/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.runtimemetrics;

import static java.util.Collections.emptyList;

import com.sun.management.GarbageCollectionNotificationInfo;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.extension.incubator.metrics.ExtendedDoubleHistogramBuilder;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Registers instruments that generate metrics about JVM garbage collection.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * GarbageCollector.registerObservers(GlobalOpenTelemetry.get());
 * }</pre>
 */
public final class GarbageCollector {

  private static final Logger logger = Logger.getLogger(GarbageCollector.class.getName());

  private static final double MILLIS_PER_S = TimeUnit.SECONDS.toMillis(1);

  private static final AttributeKey<String> GC_KEY = AttributeKey.stringKey("gc");
  private static final AttributeKey<String> ACTION_KEY = AttributeKey.stringKey("action");

  private static final NotificationFilter GC_FILTER =
      notification ->
          notification
              .getType()
              .equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION);

  /** Register observers for java runtime memory metrics. */
  public static void registerObservers(OpenTelemetry openTelemetry) {
    if (!isNotificationClassPresent()) {
      logger.fine(
          "The com.sun.management.GarbageCollectionNotificationInfo class is not available;"
              + " GC metrics will not be reported.");
      return;
    }

    registerObservers(
        openTelemetry,
        ManagementFactory.getGarbageCollectorMXBeans(),
        GarbageCollector::extractNotificationInfo);
  }

  // Visible for testing
  static void registerObservers(
      OpenTelemetry openTelemetry,
      List<GarbageCollectorMXBean> gcBeans,
      Function<Notification, GarbageCollectionNotificationInfo> notificationInfoExtractor) {
    Meter meter = RuntimeMetricsUtil.getMeter(openTelemetry);

    DoubleHistogramBuilder gcDurationBuilder =
        meter
            .histogramBuilder("process.runtime.jvm.gc.duration")
            .setDescription("Duration of JVM garbage collection actions")
            .setUnit("s");
    setGcDurationBuckets(gcDurationBuilder);
    DoubleHistogram gcDuration = gcDurationBuilder.build();

    for (GarbageCollectorMXBean gcBean : gcBeans) {
      if (!(gcBean instanceof NotificationEmitter)) {
        continue;
      }
      NotificationEmitter notificationEmitter = (NotificationEmitter) gcBean;
      notificationEmitter.addNotificationListener(
          new GcNotificationListener(gcDuration, notificationInfoExtractor), GC_FILTER, null);
    }
  }

  private static void setGcDurationBuckets(DoubleHistogramBuilder builder) {
    if (!(builder instanceof ExtendedDoubleHistogramBuilder)) {
      // that shouldn't really happen
      return;
    }
    ((ExtendedDoubleHistogramBuilder) builder)
        .setAdvice(advice -> advice.setExplicitBucketBoundaries(emptyList()));
  }

  private static final class GcNotificationListener implements NotificationListener {

    private final DoubleHistogram gcDuration;
    private final Function<Notification, GarbageCollectionNotificationInfo>
        notificationInfoExtractor;

    private GcNotificationListener(
        DoubleHistogram gcDuration,
        Function<Notification, GarbageCollectionNotificationInfo> notificationInfoExtractor) {
      this.gcDuration = gcDuration;
      this.notificationInfoExtractor = notificationInfoExtractor;
    }

    @Override
    public void handleNotification(Notification notification, Object unused) {
      GarbageCollectionNotificationInfo notificationInfo =
          notificationInfoExtractor.apply(notification);
      gcDuration.record(
          notificationInfo.getGcInfo().getDuration() / MILLIS_PER_S,
          Attributes.of(
              GC_KEY, notificationInfo.getGcName(), ACTION_KEY, notificationInfo.getGcAction()));
    }
  }

  /**
   * Extract {@link GarbageCollectionNotificationInfo} from the {@link Notification}.
   *
   * <p>Note: this exists as a separate function so that the behavior can be overridden with mocks
   * in tests. It's very challenging to create a mock {@link CompositeData} that can be parsed by
   * {@link GarbageCollectionNotificationInfo#from(CompositeData)}.
   */
  private static GarbageCollectionNotificationInfo extractNotificationInfo(
      Notification notification) {
    return GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
  }

  private static boolean isNotificationClassPresent() {
    try {
      Class.forName(
          "com.sun.management.GarbageCollectionNotificationInfo",
          false,
          GarbageCollectorMXBean.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private GarbageCollector() {}
}
