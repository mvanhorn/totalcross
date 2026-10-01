// Copyright (C) 2026 Amalgam Solucoes em TI Ltda
//
// SPDX-License-Identifier: LGPL-2.1-only

package totalcross.sys;

/** Opt-in implementation; pair this SDK variant with the matching CMake flag. */
final class RuntimeDiagnosticsSupport {
  private static final RuntimeDiagnosticSnapshot EMPTY = RuntimeDiagnosticSnapshot.empty();
  private static volatile boolean runtimeGroupEnabled;
  private static volatile boolean schedulingGroupEnabled;

  private RuntimeDiagnosticsSupport() {
  }

  static boolean isSupported() {
    return true;
  }

  static void setDomainEnabled(RuntimeDiagnosticSnapshot.Domain domain, boolean enabled) {
    if (domain == null) {
      throw new NullPointerException("domain is required");
    }
    if (enabled) {
      RuntimeMetrics.initialize();
    }
    if (domain == RuntimeDiagnosticSnapshot.Domain.RUNTIME) {
      runtimeGroupEnabled = enabled;
    } else if (domain == RuntimeDiagnosticSnapshot.Domain.SCHEDULING) {
      schedulingGroupEnabled = enabled;
    }
  }

  static boolean isSchedulingEnabledInternal() {
    return schedulingGroupEnabled;
  }

  static void recordFlickCallbackInternal(long positiveLatenessNanos) {
    if (!schedulingGroupEnabled) {
      return;
    }
    RuntimeMetrics.recordFlickCallback(positiveLatenessNanos);
  }

  static void recordFlickAdvancementInternal(long workNanos, boolean completed) {
    if (!schedulingGroupEnabled) {
      return;
    }
    RuntimeMetrics.recordFlickAdvancement(workNanos, completed);
  }

  static RuntimeDiagnosticSnapshot snapshot() {
    if (!runtimeGroupEnabled && !schedulingGroupEnabled) {
      return EMPTY;
    }
    return RuntimeMetrics.snapshot(runtimeGroupEnabled, schedulingGroupEnabled);
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
    if ((domain == RuntimeDiagnosticSnapshot.Domain.RUNTIME && !runtimeGroupEnabled)
        || (domain == RuntimeDiagnosticSnapshot.Domain.SCHEDULING && !schedulingGroupEnabled)) {
      return;
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
    private static final int FLICK_CALLBACK_COUNT_ID = 0x3001;
    private static final int FLICK_ADVANCEMENT_COUNT_ID = 0x3002;
    private static final int FLICK_COMPLETION_COUNT_ID = 0x3003;
    private static final int FLICK_ADVANCEMENT_WORK_NANOS_ID = 0x3004;
    private static final int FLICK_POSITIVE_LATENESS_NANOS_ID = 0x3005;
    private static final int[] RUNTIME_METRIC_IDS = {
        JAVA_COUNTER_ID, JAVA_GAUGE_ID, JAVA_TIMER_ID, NATIVE_COUNTER_ID, NATIVE_GAUGE_ID
    };
    private static final int[] NATIVE_METRIC_IDS = {NATIVE_COUNTER_ID, NATIVE_GAUGE_ID};
    private static final long[] NATIVE_VALUES = new long[NATIVE_METRIC_IDS.length];
    private static final Object COLLECTION_LOCK = new Object();
    private static long javaCounter;
    private static long javaGauge;
    private static long javaTimerNanos;
    private static long flickCallbackCount;
    private static long flickAdvancementCount;
    private static long flickCompletionCount;
    private static long flickAdvancementWorkNanos;
    private static long flickPositiveLatenessNanos;
    private static long epoch;
    private static NativeBridge nativeBridge = new VmNativeBridge();

    private static void initialize() {
      // Calling this method initializes this holder only after the group is enabled.
    }

    private static RuntimeDiagnosticSnapshot snapshot(boolean includeRuntime, boolean includeScheduling) {
      if (!includeRuntime && !includeScheduling) {
        return EMPTY;
      }
      synchronized (COLLECTION_LOCK) {
        includeRuntime &= runtimeGroupEnabled;
        includeScheduling &= schedulingGroupEnabled;
        if (!includeRuntime && !includeScheduling) {
          return EMPTY;
        }
        int count = (includeRuntime ? RUNTIME_METRIC_IDS.length : 0) + (includeScheduling ? 5 : 0);
        int[] metricIds = new int[count];
        byte[] domains = new byte[count];
        byte[] kinds = new byte[count];
        long[] values = new long[count];
        int output = 0;
        if (includeRuntime) {
          nativeBridge.readMetrics(NATIVE_METRIC_IDS, NATIVE_VALUES);
          byte domain = (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal();
          output = put(metricIds, domains, kinds, values, output, JAVA_COUNTER_ID, domain,
              RuntimeDiagnosticSnapshot.Kind.COUNTER, javaCounter);
          output = put(metricIds, domains, kinds, values, output, JAVA_GAUGE_ID, domain,
              RuntimeDiagnosticSnapshot.Kind.GAUGE, javaGauge);
          output = put(metricIds, domains, kinds, values, output, JAVA_TIMER_ID, domain,
              RuntimeDiagnosticSnapshot.Kind.TIMER, javaTimerNanos);
          output = put(metricIds, domains, kinds, values, output, NATIVE_COUNTER_ID, domain,
              RuntimeDiagnosticSnapshot.Kind.COUNTER, NATIVE_VALUES[0]);
          output = put(metricIds, domains, kinds, values, output, NATIVE_GAUGE_ID, domain,
              RuntimeDiagnosticSnapshot.Kind.GAUGE, NATIVE_VALUES[1]);
        }
        if (includeScheduling) {
          byte domain = (byte) RuntimeDiagnosticSnapshot.Domain.SCHEDULING.ordinal();
          output = put(metricIds, domains, kinds, values, output, FLICK_CALLBACK_COUNT_ID, domain,
              RuntimeDiagnosticSnapshot.Kind.COUNTER, flickCallbackCount);
          output = put(metricIds, domains, kinds, values, output, FLICK_ADVANCEMENT_COUNT_ID, domain,
              RuntimeDiagnosticSnapshot.Kind.COUNTER, flickAdvancementCount);
          output = put(metricIds, domains, kinds, values, output, FLICK_COMPLETION_COUNT_ID, domain,
              RuntimeDiagnosticSnapshot.Kind.COUNTER, flickCompletionCount);
          output = put(metricIds, domains, kinds, values, output, FLICK_ADVANCEMENT_WORK_NANOS_ID, domain,
              RuntimeDiagnosticSnapshot.Kind.TIMER, flickAdvancementWorkNanos);
          put(metricIds, domains, kinds, values, output, FLICK_POSITIVE_LATENESS_NANOS_ID, domain,
              RuntimeDiagnosticSnapshot.Kind.TIMER, flickPositiveLatenessNanos);
        }
        return new RuntimeDiagnosticSnapshot(metricIds, domains, kinds, values, epoch);
      }
    }

    private static int put(int[] metricIds, byte[] domains, byte[] kinds, long[] values, int index,
        int metricId, byte domain, RuntimeDiagnosticSnapshot.Kind kind, long value) {
      metricIds[index] = metricId;
      domains[index] = domain;
      kinds[index] = (byte) kind.ordinal();
      values[index] = value;
      return index + 1;
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

    private static void recordFlickCallback(long positiveLatenessNanos) {
      synchronized (COLLECTION_LOCK) {
        if (schedulingGroupEnabled) {
          flickCallbackCount++;
          flickPositiveLatenessNanos += Math.max(0L, positiveLatenessNanos);
        }
      }
    }

    private static void recordFlickAdvancement(long workNanos, boolean completed) {
      synchronized (COLLECTION_LOCK) {
        if (schedulingGroupEnabled) {
          flickAdvancementCount++;
          flickAdvancementWorkNanos += Math.max(0L, workNanos);
          if (completed) {
            flickCompletionCount++;
          }
        }
      }
    }

    private static void reset(RuntimeDiagnosticSnapshot.Domain domain) {
      synchronized (COLLECTION_LOCK) {
        if (domain == RuntimeDiagnosticSnapshot.Domain.RUNTIME && runtimeGroupEnabled) {
          javaCounter = 0L;
          javaTimerNanos = 0L;
          nativeBridge.resetMetrics(RUNTIME_GROUP_MASK);
          epoch++;
        } else if (domain == RuntimeDiagnosticSnapshot.Domain.SCHEDULING && schedulingGroupEnabled) {
          flickCallbackCount = 0L;
          flickAdvancementCount = 0L;
          flickCompletionCount = 0L;
          flickAdvancementWorkNanos = 0L;
          flickPositiveLatenessNanos = 0L;
          epoch++;
        }
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
