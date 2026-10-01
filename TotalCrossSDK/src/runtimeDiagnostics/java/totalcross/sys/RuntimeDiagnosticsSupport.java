// Copyright (C) 2026 Amalgam Solucoes em TI Ltda
//
// SPDX-License-Identifier: LGPL-2.1-only

package totalcross.sys;

import totalcross.ui.image.ImagePreparationDiagnostics;

/** Opt-in implementation; pair this SDK variant with the matching CMake flag. */
final class RuntimeDiagnosticsSupport {
  private static final RuntimeDiagnosticSnapshot EMPTY = RuntimeDiagnosticSnapshot.empty();
  private static volatile boolean runtimeGroupEnabled;
  private static volatile boolean prefetchGroupEnabled;

  private RuntimeDiagnosticsSupport() {
  }

  static boolean isSupported() {
    return true;
  }

  static void setDomainEnabled(RuntimeDiagnosticSnapshot.Domain domain, boolean enabled) {
    if (domain == null) {
      throw new NullPointerException("domain is required");
    }
    if (domain == RuntimeDiagnosticSnapshot.Domain.RUNTIME) {
      if (enabled) {
        RuntimeMetrics.initialize();
      }
      runtimeGroupEnabled = enabled;
    } else if (domain == RuntimeDiagnosticSnapshot.Domain.PREFETCH) {
      ImagePreparationDiagnostics.setEnabled(enabled);
      prefetchGroupEnabled = enabled;
    }
  }

  static RuntimeDiagnosticSnapshot snapshot() {
    if (!runtimeGroupEnabled && !prefetchGroupEnabled) {
      return EMPTY;
    }
    return RuntimeMetrics.snapshot();
  }

  static void addJavaCounterForTest(long delta) {
    if (!runtimeGroupEnabled) {
      return;
    }
    RuntimeMetrics.addJavaCounter(delta);
  }

  static void setJavaGaugeForTest(long value) {
    if (!runtimeGroupEnabled) {
      return;
    }
    RuntimeMetrics.setJavaGauge(value);
  }

  static void addJavaTimerForTest(long elapsedNanos) {
    if (!runtimeGroupEnabled) {
      return;
    }
    RuntimeMetrics.addJavaTimer(elapsedNanos);
  }

  static void addNativeCounterForTest(long delta) {
    if (!runtimeGroupEnabled) {
      return;
    }
    RuntimeMetrics.addNativeCounter(delta);
  }

  static void setNativeGaugeForTest(long value) {
    if (!runtimeGroupEnabled) {
      return;
    }
    RuntimeMetrics.setNativeGauge(value);
  }

  static long readSingleNativeMetricForTest() {
    if (!runtimeGroupEnabled) {
      return 0L;
    }
    return RuntimeMetrics.readSingleNativeMetric();
  }

  static void resetForTest(RuntimeDiagnosticSnapshot.Domain domain) {
    if (domain == null) {
      throw new NullPointerException("domain is required");
    }
    RuntimeMetrics.reset(domain);
  }

  private static final class RuntimeMetrics {
    // These bounded synthetic observations exercise the shared snapshot path.
    private static final int JAVA_COUNTER_ID = 0x1001;
    private static final int JAVA_GAUGE_ID = 0x1002;
    private static final int JAVA_TIMER_ID = 0x1003;
    private static final int NATIVE_COUNTER_ID = 0x2001;
    private static final int NATIVE_GAUGE_ID = 0x2002;
    private static final int RUNTIME_GROUP_MASK = 1;
    private static final int[] RUNTIME_METRIC_IDS = {
        JAVA_COUNTER_ID, JAVA_GAUGE_ID, JAVA_TIMER_ID, NATIVE_COUNTER_ID, NATIVE_GAUGE_ID
    };
    private static final int[] PREFETCH_METRIC_IDS = {
        0x3001, 0x3002, 0x3003, 0x3004, 0x3005, 0x3006, 0x3007, 0x3008, 0x3009, 0x300A
    };
    private static final byte[] RUNTIME_DOMAINS = {
        (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal()
    };
    private static final byte[] RUNTIME_KINDS = {
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.GAUGE.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.TIMER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.GAUGE.ordinal()
    };
    private static final byte[] PREFETCH_DOMAINS = {
        (byte) RuntimeDiagnosticSnapshot.Domain.PREFETCH.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.PREFETCH.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.PREFETCH.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.PREFETCH.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.PREFETCH.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.PREFETCH.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.PREFETCH.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.PREFETCH.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.PREFETCH.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.PREFETCH.ordinal()
    };
    private static final byte[] PREFETCH_KINDS = {
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.GAUGE.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.GAUGE.ordinal()
    };
    private static final int[] NATIVE_METRIC_IDS = {NATIVE_COUNTER_ID, NATIVE_GAUGE_ID};
    private static final long[] NATIVE_VALUES = new long[NATIVE_METRIC_IDS.length];
    private static final long[] PREFETCH_VALUES = new long[PREFETCH_METRIC_IDS.length];
    private static final Object COLLECTION_LOCK = new Object();
    private static long javaCounter;
    private static long javaGauge;
    private static long javaTimerNanos;
    private static long epoch;
    private static NativeBridge nativeBridge = new VmNativeBridge();

    private static void initialize() {
      // Calling this method initializes this holder only after the group is enabled.
    }

    private static RuntimeDiagnosticSnapshot snapshot() {
      if (!runtimeGroupEnabled && !prefetchGroupEnabled) {
        return EMPTY;
      }
      synchronized (COLLECTION_LOCK) {
        boolean includeRuntime = runtimeGroupEnabled;
        boolean includePrefetch = prefetchGroupEnabled;
        if (!includeRuntime && !includePrefetch) {
          return EMPTY;
        }
        int runtimeCount = includeRuntime ? RUNTIME_METRIC_IDS.length : 0;
        int prefetchCount = includePrefetch ? PREFETCH_METRIC_IDS.length : 0;
        int count = runtimeCount + prefetchCount;
        int[] ids = new int[count];
        byte[] domains = new byte[count];
        byte[] kinds = new byte[count];
        long[] values = new long[count];
        int offset = 0;
        if (includeRuntime) {
          nativeBridge.readMetrics(NATIVE_METRIC_IDS, NATIVE_VALUES);
          System.arraycopy(RUNTIME_METRIC_IDS, 0, ids, offset, runtimeCount);
          System.arraycopy(RUNTIME_DOMAINS, 0, domains, offset, runtimeCount);
          System.arraycopy(RUNTIME_KINDS, 0, kinds, offset, runtimeCount);
          values[offset] = javaCounter;
          values[offset + 1] = javaGauge;
          values[offset + 2] = javaTimerNanos;
          values[offset + 3] = NATIVE_VALUES[0];
          values[offset + 4] = NATIVE_VALUES[1];
          offset += runtimeCount;
        }
        if (includePrefetch) {
          ImagePreparationDiagnostics.copyValues(PREFETCH_VALUES);
          System.arraycopy(PREFETCH_METRIC_IDS, 0, ids, offset, prefetchCount);
          System.arraycopy(PREFETCH_DOMAINS, 0, domains, offset, prefetchCount);
          System.arraycopy(PREFETCH_KINDS, 0, kinds, offset, prefetchCount);
          System.arraycopy(PREFETCH_VALUES, 0, values, offset, prefetchCount);
        }
        return new RuntimeDiagnosticSnapshot(ids, domains, kinds, values, epoch);
      }
    }

    private static void addJavaCounter(long delta) {
      synchronized (COLLECTION_LOCK) {
        if (runtimeGroupEnabled) {
          javaCounter += delta;
        }
      }
    }

    private static void setJavaGauge(long value) {
      synchronized (COLLECTION_LOCK) {
        if (runtimeGroupEnabled) {
          javaGauge = value;
        }
      }
    }

    private static void addJavaTimer(long elapsedNanos) {
      synchronized (COLLECTION_LOCK) {
        if (runtimeGroupEnabled) {
          javaTimerNanos += elapsedNanos;
        }
      }
    }

    private static void addNativeCounter(long delta) {
      synchronized (COLLECTION_LOCK) {
        if (runtimeGroupEnabled) {
          nativeBridge.addCounterForTest(delta);
        }
      }
    }

    private static void setNativeGauge(long value) {
      synchronized (COLLECTION_LOCK) {
        if (runtimeGroupEnabled) {
          nativeBridge.setGaugeForTest(value);
        }
      }
    }

    private static long readSingleNativeMetric() {
      synchronized (COLLECTION_LOCK) {
        return runtimeGroupEnabled ? nativeBridge.readMetric(NATIVE_COUNTER_ID) : 0L;
      }
    }

    private static void reset(RuntimeDiagnosticSnapshot.Domain domain) {
      synchronized (COLLECTION_LOCK) {
        if (domain == RuntimeDiagnosticSnapshot.Domain.RUNTIME && runtimeGroupEnabled) {
          javaCounter = 0L;
          javaTimerNanos = 0L;
          nativeBridge.resetMetrics(RUNTIME_GROUP_MASK);
        }
        if (domain == RuntimeDiagnosticSnapshot.Domain.PREFETCH && prefetchGroupEnabled) {
          ImagePreparationDiagnostics.reset();
        }
        epoch++;
      }
    }
  }

  interface NativeBridge {
    long readMetric(int metricId);

    void readMetrics(int[] metricIds, long[] values);

    void resetMetrics(int groupMask);

    void addCounterForTest(long delta);

    void setGaugeForTest(long value);
  }

  private static final class VmNativeBridge implements NativeBridge {
    @Override
    public long readMetric(int metricId) {
      return readMetricNative(metricId);
    }

    @Override
    public void readMetrics(int[] metricIds, long[] values) {
      readMetricsNative(metricIds, values);
    }

    @Override
    public void resetMetrics(int groupMask) {
      resetMetricsNative(groupMask);
    }

    @Override
    public void addCounterForTest(long delta) {
      addNativeCountNative(delta);
    }

    @Override
    public void setGaugeForTest(long value) {
      setNativeGaugeNative(value);
    }
  }

  private static native long readMetricNative(int metricId);

  private static native void readMetricsNative(int[] metricIds, long[] values);

  private static native void resetMetricsNative(int groupMask);

  private static native void addNativeCountNative(long delta);

  private static native void setNativeGaugeNative(long value);
}
