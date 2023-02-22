/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.runtimemetricsjfr;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.GarbageCollection.G1GarbageCollectionHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.GarbageCollection.OldGarbageCollectionHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.GarbageCollection.YoungGarbageCollectionHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.RecordedEventHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.ThreadGrouper;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.buffer.DirectBufferStatisticsHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.classes.ClassesLoadedHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.container.ContainerConfigurationHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.cpu.ContextSwitchRateHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.cpu.LongLockHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.cpu.OverallCPULoadHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.memory.CodeCacheConfigurationHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.memory.G1HeapSummaryHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.memory.MetaspaceSummaryHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.memory.ObjectAllocationInNewTLABHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.memory.ObjectAllocationOutsideTLABHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.memory.ParallelHeapSummaryHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.network.NetworkReadHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.network.NetworkWriteHandler;
import io.opentelemetry.instrumentation.runtimemetricsjfr.internal.threads.ThreadCountHandler;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

final class HandlerRegistry {
  private static final String SCOPE_NAME = "io.opentelemetry.instrumentation.runtimemetricsjfr";
  private static final String SCOPE_VERSION = readVersion();

  private HandlerRegistry() {}

  static List<RecordedEventHandler> getHandlers(
      OpenTelemetry openTelemetry, Predicate<JfrFeature> featurePredicate) {
    Meter meter =
        openTelemetry
            .getMeterProvider()
            .meterBuilder(SCOPE_NAME)
            .setInstrumentationVersion(SCOPE_VERSION)
            .build();

    List<RecordedEventHandler> handlers = new ArrayList<RecordedEventHandler>();
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      String name = bean.getName();
      switch (name) {
        case "G1 Young Generation":
          handlers.add(new G1HeapSummaryHandler(meter));
          handlers.add(new G1GarbageCollectionHandler(meter));
          break;

        case "Copy":
          handlers.add(new YoungGarbageCollectionHandler(meter, name));
          break;

        case "PS Scavenge":
          handlers.add(new YoungGarbageCollectionHandler(meter, name));
          handlers.add(new ParallelHeapSummaryHandler(meter));
          break;

        case "G1 Old Generation":
        case "PS MarkSweep":
        case "MarkSweepCompact":
          handlers.add(new OldGarbageCollectionHandler(meter, name));
          break;

        default:
          // If none of the above GCs are detected, no action.
      }
    }

    ThreadGrouper grouper = new ThreadGrouper();
    List<RecordedEventHandler> basicHandlers =
        List.of(
            new ObjectAllocationInNewTLABHandler(meter, grouper),
            new ObjectAllocationOutsideTLABHandler(meter, grouper),
            new NetworkReadHandler(meter, grouper),
            new NetworkWriteHandler(meter, grouper),
            new ContextSwitchRateHandler(meter),
            new OverallCPULoadHandler(meter),
            new ContainerConfigurationHandler(meter),
            new LongLockHandler(meter, grouper),
            new ThreadCountHandler(meter),
            new ClassesLoadedHandler(meter),
            new MetaspaceSummaryHandler(meter),
            new CodeCacheConfigurationHandler(meter),
            new DirectBufferStatisticsHandler(meter));
    handlers.addAll(basicHandlers);

    // Filter and close disabled handlers
    Iterator<RecordedEventHandler> iter = handlers.iterator();
    while (iter.hasNext()) {
      RecordedEventHandler handler = iter.next();
      if (!featurePredicate.test(handler.getFeature())) {
        try {
          handler.close();
        } catch (IOException e) {
          // Ignored
        }
        iter.remove();
      }
    }

    return handlers;
  }

  private static String readVersion() {
    Properties properties = new Properties();
    try {
      properties.load(HandlerRegistry.class.getResourceAsStream("version.properties"));
    } catch (Exception e) {
      // we left the attribute empty
      return "unknown";
    }
    return properties.getProperty("contrib.version", "unknown");
  }
}