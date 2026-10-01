// Copyright (C) 2026 Amalgam Solucoes em TI Ltda
//
// SPDX-License-Identifier: LGPL-2.1-only

package totalcross.sys;

/** Opt-in implementation; pair this SDK variant with the matching CMake flag. */
final class RuntimeDiagnosticsSupport {
  private static final RuntimeDiagnosticSnapshot EMPTY = RuntimeDiagnosticSnapshot.empty();
  private static volatile boolean runtimeGroupEnabled;
  private static volatile boolean renderingGroupEnabled;

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
      runtimeGroupEnabled = enabled;
    } else if (domain == RuntimeDiagnosticSnapshot.Domain.RENDERING) {
      renderingGroupEnabled = enabled;
    }
    if (enabled) {
      RuntimeMetrics.initialize();
    }
  }

  static RuntimeDiagnosticSnapshot snapshot() {
    if (!runtimeGroupEnabled && !renderingGroupEnabled) {
      return EMPTY;
    }
    return RuntimeMetrics.snapshot();
  }

  static void recordRenderingReuseAttempt() {
    if (renderingGroupEnabled) {
      RuntimeMetrics.addRenderingCounter(0);
    }
  }

  static void recordRenderingReuseSuccess() {
    if (renderingGroupEnabled) {
      RuntimeMetrics.addRenderingCounter(1);
    }
  }

  static void recordRenderingReuseFallback() {
    if (renderingGroupEnabled) {
      RuntimeMetrics.addRenderingCounter(2);
    }
  }

  static void recordRenderingMoveRecovered() {
    if (renderingGroupEnabled) {
      RuntimeMetrics.addRenderingCounter(3);
    }
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
    if (!runtimeGroupEnabled && !renderingGroupEnabled) {
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
    private static final int REUSE_ATTEMPT_ID = 0x3001;
    private static final int REUSE_SUCCESS_ID = 0x3002;
    private static final int REUSE_FALLBACK_ID = 0x3003;
    private static final int MOVE_RECOVERED_ID = 0x3004;
    private static final int RUNTIME_GROUP_MASK = 1;
    private static final int[] METRIC_IDS = {
        JAVA_COUNTER_ID, JAVA_GAUGE_ID, JAVA_TIMER_ID, NATIVE_COUNTER_ID, NATIVE_GAUGE_ID,
        REUSE_ATTEMPT_ID, REUSE_SUCCESS_ID, REUSE_FALLBACK_ID, MOVE_RECOVERED_ID
    };
    private static final byte[] DOMAINS = {
        (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RUNTIME.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RENDERING.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RENDERING.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RENDERING.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Domain.RENDERING.ordinal()
    };
    private static final byte[] KINDS = {
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.GAUGE.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.TIMER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.GAUGE.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal(),
        (byte) RuntimeDiagnosticSnapshot.Kind.COUNTER.ordinal()
    };
    private static final int[] NATIVE_METRIC_IDS = {NATIVE_COUNTER_ID, NATIVE_GAUGE_ID};
    private static final long[] NATIVE_VALUES = new long[NATIVE_METRIC_IDS.length];
    private static final Object COLLECTION_LOCK = new Object();
    private static long javaCounter;
    private static long javaGauge;
    private static long javaTimerNanos;
    private static long reuseAttempts;
    private static long reuseSuccesses;
    private static long reuseFallbacks;
    private static long moveRecovered;
    private static long epoch;
    private static NativeBridge nativeBridge = new VmNativeBridge();

    private static void initialize() {
      // Calling this method initializes this holder only after the group is enabled.
    }

    private static RuntimeDiagnosticSnapshot snapshot() {
      if (!runtimeGroupEnabled && !renderingGroupEnabled) {
        return EMPTY;
      }
      synchronized (COLLECTION_LOCK) {
        boolean includeRuntime = runtimeGroupEnabled;
        boolean includeRendering = renderingGroupEnabled;
        if (!includeRuntime && !includeRendering) {
          return EMPTY;
        }
        long nativeCounter = 0L;
        long nativeGauge = 0L;
        if (includeRuntime) {
          nativeBridge.readMetrics(NATIVE_METRIC_IDS, NATIVE_VALUES);
          nativeCounter = NATIVE_VALUES[0];
          nativeGauge = NATIVE_VALUES[1];
        }
        long[] values = {
            includeRuntime ? javaCounter : 0L,
            includeRuntime ? javaGauge : 0L,
            includeRuntime ? javaTimerNanos : 0L,
            nativeCounter,
            nativeGauge,
            includeRendering ? reuseAttempts : 0L,
            includeRendering ? reuseSuccesses : 0L,
            includeRendering ? reuseFallbacks : 0L,
            includeRendering ? moveRecovered : 0L
        };
        return new RuntimeDiagnosticSnapshot(METRIC_IDS, DOMAINS, KINDS, values, epoch);
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

    private static void addRenderingCounter(int counter) {
      synchronized (COLLECTION_LOCK) {
        if (!renderingGroupEnabled) {
          return;
        }
        switch (counter) {
        case 0:
          reuseAttempts++;
          break;
        case 1:
          reuseSuccesses++;
          break;
        case 2:
          reuseFallbacks++;
          break;
        case 3:
          moveRecovered++;
          break;
        default:
          throw new IllegalArgumentException("unknown rendering counter");
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
          epoch++;
        } else if (domain == RuntimeDiagnosticSnapshot.Domain.RENDERING && renderingGroupEnabled) {
          reuseAttempts = 0L;
          reuseSuccesses = 0L;
          reuseFallbacks = 0L;
          moveRecovered = 0L;
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
