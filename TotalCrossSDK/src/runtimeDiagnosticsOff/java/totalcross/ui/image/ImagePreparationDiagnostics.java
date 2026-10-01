// Copyright (C) 2026 Amalgam Solucoes em TI Ltda
//
// SPDX-License-Identifier: LGPL-2.1-only

package totalcross.ui.image;

/**
 * Compile-out internal bridge for PREFETCH observations.
 *
 * @hidden
 * @deprecated This class is for TotalCross internals only and is not a supported API.
 */
@Deprecated
public final class ImagePreparationDiagnostics {
  private ImagePreparationDiagnostics() {
  }

  public static void setEnabled(boolean enabled) {
  }

  public static void copyValues(long[] output) {
    if (output == null || output.length < 10) {
      throw new IllegalArgumentException("Prefetch metric output is too small");
    }
    for (int i = 0; i < 10; i++) {
      output[i] = 0;
    }
  }

  public static void reset() {
  }

  static void discovered() { }
  static void enqueued() { }
  static void deduplicated() { }
  static void ready() { }
  static void stale() { }
  static void notPrefetchable() { }
  static void deterministicFailure() { }
  static void transientFailure() { }
  static void queueState(int depth, int active) { }
}
