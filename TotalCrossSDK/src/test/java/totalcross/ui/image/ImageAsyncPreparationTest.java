// Copyright (C) 2026 Amalgam Solucoes em TI Ltda
//
// SPDX-License-Identifier: LGPL-2.1-only

package totalcross.ui.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import totalcross.ui.MainWindow;

class ImageAsyncPreparationTest {
  @BeforeAll
  static void initializeRuntime() {
    new tc.simulator.Launcher();
    if (MainWindow.getMainWindow() == null) {
      new MainWindow();
    }
  }

  @Test
  void requestIdentityKeepsSourcePipelineScaleAndBatchDistinct() throws Exception {
    Path path = writeJpeg(jpeg(96, 64));
    try {
      Image image = Image.getJpegScaled(path.toString(), 1, 2);
      double scale = MainWindow.getMainWindow().getGraphics().getContentScale();
      ImagePreparationRequest first = image.captureDisplayPreparationRequest(scale, 3L);
      ImagePreparationRequest laterBatch = image.captureDisplayPreparationRequest(scale, 9L);
      ImagePreparationRequest nextScale = image.captureDisplayPreparationRequest(Math.nextUp(scale), 3L);
      Image otherPipeline = image.getSmoothScaledInstance(24, 16);
      ImagePreparationRequest differentPipeline = otherPipeline.captureDisplayPreparationRequest(scale, 3L);
      Image samePath = Image.getJpegScaled(path.toString(), 1, 2);
      ImagePreparationRequest differentSource = samePath.captureDisplayPreparationRequest(scale, 3L);

      assertTrue(first.equivalentTo(laterBatch));
      assertFalse(first.equivalentTo(nextScale));
      assertFalse(first.equivalentTo(differentPipeline));
      assertFalse(first.equivalentTo(differentSource));
      assertNotSame(first.source, differentSource.source);
      assertEquals(ImagePreparationRequest.Readiness.DRAW_READY, first.readiness);

      ImagePreparationRequest copyReady = copyRequest(first,
          ImagePreparationRequest.Readiness.COPY_READY, first.prototype);
      assertTrue(copyReady.equivalentTo(first));
      assertFalse(first.equivalentTo(copyReady));
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void detachedWorkerLeavesLiveImageUntouchedAndAdoptionFeedsTheNextDraw() throws Exception {
    Image image = lazyImage(jpeg(128, 96));
    double scale = MainWindow.getMainWindow().getGraphics().getContentScale();
    ImagePreparationRequest request = image.captureDisplayPreparationRequest(scale, 4L);
    ImagePipeline capturedPipeline = image.pipelineForSmoke();
    EncodedImageSource source = request.source;
    long generation = source.decodedGeneration();
    Image.resetImageOperationAccountingForTest();

    PreparedImageResult result = request.prototype.prepareDetachedForDisplay(request);

    assertEquals(PreparedImageResult.FailureKind.NONE, result.failureKind);
    assertSame(capturedPipeline, image.pipelineForSmoke());
    assertEquals(generation, source.decodedGeneration());
    assertNull(source.decodeFailure());
    assertEquals(ImagePreparationScheduler.TerminalState.READY,
        image.adoptPreparedForDisplay(request, result));
    Image ready = image.resolveForDrawing(scale);
    assertSame(result.variant, ready);
    assertEquals(1, Image.targetedDecodeInvocationCountForTest());
    result.releaseDetachedEncodedSource();
  }

  @Test
  void stalePipelineAndScaleAreRejectedButAnOldBatchCanStillAdopt() throws Exception {
    Image changedPipeline = lazyImage(jpeg(96, 64));
    double scale = MainWindow.getMainWindow().getGraphics().getContentScale();
    ImagePreparationRequest stalePipeline = changedPipeline.captureDisplayPreparationRequest(scale, 1L);
    changedPipeline.applyColor2(0xFF4080C0);
    assertEquals(ImagePreparationScheduler.TerminalState.STALE,
        changedPipeline.adoptPreparedForDisplay(stalePipeline, PreparedImageResult.transientFailure(null)));

    Image changedScale = lazyImage(jpeg(96, 64));
    ImagePreparationRequest captured = changedScale.captureDisplayPreparationRequest(scale, 2L);
    ImagePreparationRequest mismatchedScale = copyRequest(captured, captured.readiness,
        captured.prototype, Math.nextUp(scale));
    assertEquals(ImagePreparationScheduler.TerminalState.STALE,
        changedScale.adoptPreparedForDisplay(mismatchedScale, PreparedImageResult.transientFailure(null)));

    Image oldBatchTarget = lazyImage(jpeg(96, 64));
    ImagePreparationRequest oldBatch = oldBatchTarget.captureDisplayPreparationRequest(scale, 3L);
    // The batch generation is callback identity only, so an older batch can still prepare a valid Image.
    PreparedImageResult usefulResult = oldBatch.prototype.prepareDetachedForDisplay(oldBatch);
    assertEquals(ImagePreparationScheduler.TerminalState.READY,
        oldBatchTarget.adoptPreparedForDisplay(oldBatch, usefulResult));
    usefulResult.releaseDetachedEncodedSource();
  }

  @Test
  void newerCompatibleSynchronousDecodeWinsTheSourceGenerationRace() throws Exception {
    Image image = lazyImage(jpeg(128, 96));
    double scale = MainWindow.getMainWindow().getGraphics().getContentScale();
    ImagePreparationRequest request = image.captureDisplayPreparationRequest(scale, 5L);
    PreparedImageResult detached = request.prototype.prepareDetachedForDisplay(request);
    Image synchronous = image.resolveForDrawing(scale);
    ImageBacking synchronousBacking = request.source.decodedBackingForReuse(request.decodeDenominator);
    long winningGeneration = request.source.decodedGeneration();

    assertEquals(ImagePreparationScheduler.TerminalState.READY,
        image.adoptPreparedForDisplay(request, detached));
    assertEquals(winningGeneration, request.source.decodedGeneration());
    assertSame(synchronousBacking, request.source.decodedBackingForReuse(request.decodeDenominator));
    assertSame(synchronous, image.resolveForDrawing(scale));
    detached.releaseDetachedEncodedSource();
  }

  @Test
  void transientFailureRetriesAndDeterministicFailureIsCachedAtAdoption() throws Exception {
    Image retry = lazyImage(jpeg(96, 64));
    double scale = MainWindow.getMainWindow().getGraphics().getContentScale();
    ImagePreparationRequest firstAttempt = retry.captureDisplayPreparationRequest(scale, 6L);
    Image.failNextTargetedDecodeInfrastructureForTest();
    PreparedImageResult temporary = firstAttempt.prototype.prepareDetachedForDisplay(firstAttempt);
    assertEquals(PreparedImageResult.FailureKind.TRANSIENT, temporary.failureKind);
    assertNull(firstAttempt.source.decodeFailure());
    assertEquals(ImagePreparationScheduler.TerminalState.TRANSIENT_FAILURE,
        retry.adoptPreparedForDisplay(firstAttempt, temporary));

    ImagePreparationRequest secondAttempt = retry.captureDisplayPreparationRequest(scale, 7L);
    PreparedImageResult successful = secondAttempt.prototype.prepareDetachedForDisplay(secondAttempt);
    assertEquals(PreparedImageResult.FailureKind.NONE, successful.failureKind);
    assertEquals(ImagePreparationScheduler.TerminalState.READY,
        retry.adoptPreparedForDisplay(secondAttempt, successful));
    successful.releaseDetachedEncodedSource();

    Image corrupt = lazyImage(corruptJpegEntropy(jpeg(64, 48)));
    ImagePreparationRequest corruptRequest = corrupt.captureDisplayPreparationRequest(scale, 8L);
    PreparedImageResult invalid = corruptRequest.prototype.prepareDetachedForDisplay(corruptRequest);
    assertEquals(PreparedImageResult.FailureKind.DETERMINISTIC, invalid.failureKind);
    assertEquals(ImagePreparationScheduler.TerminalState.DETERMINISTIC_FAILURE,
        corrupt.adoptPreparedForDisplay(corruptRequest, invalid));
    assertSame(invalid.failure, corruptRequest.source.decodeFailure());
    final int[] settled = {0};
    ImagePreparationScheduler.submit(corruptRequest, new Runnable() {
      @Override
      public void run() {
        settled[0]++;
      }
    });
    assertEquals(1, settled[0]);
    assertTrue(ImagePreparationScheduler.idleForTest());
    invalid.releaseDetachedEncodedSource();
  }

  @Test
  void globalFifoStaysSingleActiveThroughAdoptionAndAllowsCallbackReentrancy() throws Exception {
    awaitSchedulerIdle();
    final Thread uiThread = Thread.currentThread();
    Image first = lazyImage(jpeg(96, 64));
    Image second = lazyImage(jpeg(96, 64));
    Image third = lazyImage(jpeg(96, 64));
    double scale = MainWindow.getMainWindow().getGraphics().getContentScale();
    ImagePreparationRequest firstRequest = first.captureDisplayPreparationRequest(scale, 10L);
    ImagePreparationRequest secondRequest = second.captureDisplayPreparationRequest(scale, 11L);
    ImagePreparationRequest thirdRequest = third.captureDisplayPreparationRequest(scale, 12L);
    final ImagePreparationRequest queuedSecond = secondRequest;
    final ImagePreparationRequest queuedThird = thirdRequest;
    final ImagePreparationRequest firstPending = firstRequest;
    final ArrayList<Integer> order = new ArrayList<Integer>();
    final Thread[] callbackThreads = new Thread[4];
    final int[] maxActive = {0};
    Image.resetImageOperationAccountingForTest();

    try {
      ImagePreparationScheduler.submit(firstPending, new Runnable() {
        @Override
        public void run() {
          callbackThreads[0] = Thread.currentThread();
          maxActive[0] = Math.max(maxActive[0], ImagePreparationScheduler.activeCountForTest());
          order.add(1);
          ImagePreparationScheduler.submit(queuedThird, new Runnable() {
            @Override
            public void run() {
              callbackThreads[3] = Thread.currentThread();
              maxActive[0] = Math.max(maxActive[0], ImagePreparationScheduler.activeCountForTest());
              order.add(3);
            }
          });
        }
      });
      ImagePreparationScheduler.submit(firstPending, new Runnable() {
        @Override
        public void run() {
          callbackThreads[1] = Thread.currentThread();
          maxActive[0] = Math.max(maxActive[0], ImagePreparationScheduler.activeCountForTest());
          order.add(11);
        }
      });
      assertEquals(0, ImagePreparationScheduler.queueDepthForTest());
      ImagePreparationScheduler.submit(queuedSecond, new Runnable() {
        @Override
        public void run() {
          callbackThreads[2] = Thread.currentThread();
          maxActive[0] = Math.max(maxActive[0], ImagePreparationScheduler.activeCountForTest());
          order.add(2);
        }
      });
      assertEquals(1, ImagePreparationScheduler.activeCountForTest());
      assertEquals(1, ImagePreparationScheduler.queueDepthForTest());
      pumpUntil(new CompletionCheck() {
        @Override
        public boolean isComplete() {
          return order.size() == 4 && ImagePreparationScheduler.idleForTest();
        }
      });

      assertEquals(1, order.get(0));
      assertEquals(11, order.get(1));
      assertEquals(2, order.get(2));
      assertEquals(3, order.get(3));
      assertSame(uiThread, callbackThreads[0]);
      assertSame(uiThread, callbackThreads[1]);
      assertSame(uiThread, callbackThreads[2]);
      assertSame(uiThread, callbackThreads[3]);
      assertEquals(1, maxActive[0]);
      assertEquals(3, Image.targetedDecodeInvocationCountForTest());
      assertEquals(0, ImagePreparationScheduler.activeCountForTest());
    } finally {
      pumpUntil(new CompletionCheck() {
        @Override
        public boolean isComplete() {
          return ImagePreparationScheduler.idleForTest();
        }
      });
    }
  }

  @Test
  void pendingRegistryIsBoundedAndOverflowCanRetryLater() throws Exception {
    awaitSchedulerIdle();
    int limit = ImagePreparationScheduler.pendingLimitForTest();
    int requestCount = limit + 2;
    byte[] encoded = jpeg(32, 24);
    double scale = MainWindow.getMainWindow().getGraphics().getContentScale();
    ArrayList<ImagePreparationRequest> requests = new ArrayList<ImagePreparationRequest>(requestCount);
    for (int i = 0; i < requestCount; i++) {
      requests.add(lazyImage(encoded).captureDisplayPreparationRequest(scale, 20L + i));
    }

    final int[] callbacks = {0};
    for (ImagePreparationRequest request : requests) {
      ImagePreparationScheduler.submit(request, new Runnable() {
        @Override
        public void run() {
          callbacks[0]++;
        }
      });
    }

    assertEquals(limit, ImagePreparationScheduler.pendingCountForTest());
    assertEquals(limit - 1, ImagePreparationScheduler.queueDepthForTest());
    assertEquals(1, ImagePreparationScheduler.activeCountForTest());
    assertEquals(2, callbacks[0]);
    pumpUntil(new CompletionCheck() {
      @Override
      public boolean isComplete() {
        return callbacks[0] == requestCount && ImagePreparationScheduler.idleForTest();
      }
    }, 30);

    ImagePreparationScheduler.submit(requests.get(limit), new Runnable() {
      @Override
      public void run() {
        callbacks[0]++;
      }
    });
    pumpUntil(new CompletionCheck() {
      @Override
      public boolean isComplete() {
        return callbacks[0] == requestCount + 1 && ImagePreparationScheduler.idleForTest();
      }
    });
  }

  private static Image lazyImage(byte[] encoded) throws Exception {
    Path path = writeJpeg(encoded);
    try {
      return Image.getJpegScaled(path.toString(), 1, 2);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  private static Path writeJpeg(byte[] encoded) throws Exception {
    Path path = Files.createTempFile("tc-async-image", ".jpg");
    Files.write(path, encoded);
    return path;
  }

  private static byte[] jpeg(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int red = (x * 29 + y * 17) & 0xFF;
        image.setRGB(x, y, (red << 16) | (((red * 3) & 0xFF) << 8) | ((red * 7) & 0xFF));
      }
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    ImageWriter writer = writers.next();
    try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
      writer.setOutput(output);
      writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
    } finally {
      writer.dispose();
    }
    return bytes.toByteArray();
  }

  private static byte[] corruptJpegEntropy(byte[] source) {
    int sos = -1;
    for (int i = 0; i + 1 < source.length; i++) {
      if ((source[i] & 0xFF) == 0xFF && (source[i + 1] & 0xFF) == 0xDA) {
        sos = i;
        break;
      }
    }
    int segmentLength = ((source[sos + 2] & 0xFF) << 8) | (source[sos + 3] & 0xFF);
    int entropy = sos + 2 + segmentLength;
    byte[] invalidTail = new byte[] {
        (byte) 0xFF, (byte) 0xC3, 0, 8, 8, 0, 1, 0, 1, 1, (byte) 0xFF, (byte) 0xD9
    };
    byte[] result = new byte[entropy + invalidTail.length];
    System.arraycopy(source, 0, result, 0, entropy);
    System.arraycopy(invalidTail, 0, result, entropy, invalidTail.length);
    return result;
  }

  private static ImagePreparationRequest copyRequest(ImagePreparationRequest request,
      ImagePreparationRequest.Readiness readiness, Image prototype) {
    return copyRequest(request, readiness, prototype, request.destinationScale());
  }

  private static ImagePreparationRequest copyRequest(ImagePreparationRequest request,
      ImagePreparationRequest.Readiness readiness, Image prototype, double destinationScale) {
    return new ImagePreparationRequest(request.target, request.source, request.pipeline, request.decodePolicy,
        Double.doubleToLongBits(destinationScale), destinationScale,
        request.requestedWidth, request.requestedHeight,
        request.decodeDenominator, request.sourceGeneration, request.effectivePolicy, request.currentFrame,
        request.imageWidth, request.imageHeight, request.logicalWidth, request.logicalHeight,
        readiness, request.batchGeneration, prototype);
  }

  private static void awaitSchedulerIdle() throws Exception {
    pumpUntil(new CompletionCheck() {
      @Override
      public boolean isComplete() {
        return ImagePreparationScheduler.idleForTest();
      }
    });
  }

  private static void pumpUntil(CompletionCheck condition) throws Exception {
    pumpUntil(condition, 8);
  }

  private static void pumpUntil(CompletionCheck condition, int timeoutSeconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    MainWindow mainWindow = MainWindow.getMainWindow();
    while (!condition.isComplete() && System.nanoTime() < deadline) {
      mainWindow._onTimerTick(false);
      Thread.sleep(4);
    }
    assertTrue(condition.isComplete(), "Timed out waiting for image preparation callbacks");
  }

  private interface CompletionCheck {
    boolean isComplete();
  }

}
