// Copyright (C) 2026 Amalgam Solucoes em TI Ltda
//
// SPDX-License-Identifier: LGPL-2.1-only

package totalcross.ui.image;

/**
 * Internal, unsupported aggregate bridge used by RuntimeDiagnostics.
 *
 * @hidden
 * @deprecated This class is for TotalCross internals only and is not a supported API.
 */
@Deprecated
public final class ImagePreparationDiagnostics {
  private static final Object LOCK = new Object();
  private static final long[] VALUES = new long[10];
  private static volatile boolean enabled;

  private ImagePreparationDiagnostics() {
  }

  public static void setEnabled(boolean value) {
    enabled = value;
  }

  public static void copyValues(long[] output) {
    if (output == null || output.length < VALUES.length) {
      throw new IllegalArgumentException("Prefetch metric output is too small");
    }
    if (!enabled) {
      for (int i = 0; i < VALUES.length; i++) {
        output[i] = 0;
      }
      return;
    }
    synchronized (LOCK) {
      System.arraycopy(VALUES, 0, output, 0, VALUES.length);
    }
  }

  public static void reset() {
    synchronized (LOCK) {
      for (int i = 0; i < VALUES.length; i++) {
        VALUES[i] = 0;
      }
    }
  }

  static void discovered() { add(0, 1); }
  static void enqueued() { add(1, 1); }
  static void deduplicated() { add(2, 1); }
  static void ready() { add(3, 1); }
  static void stale() { add(4, 1); }
  static void notPrefetchable() { add(5, 1); }
  static void deterministicFailure() { add(6, 1); }
  static void transientFailure() { add(7, 1); }

  static void queueState(int depth, int active) {
    if (!enabled) {
      return;
    }
    synchronized (LOCK) {
      VALUES[8] = depth;
      VALUES[9] = active;
    }
  }

  private static void add(int index, long amount) {
    if (!enabled) {
      return;
    }
    synchronized (LOCK) {
      if (enabled) {
        VALUES[index] += amount;
      }
    }
  }
}
