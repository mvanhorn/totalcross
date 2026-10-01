// Copyright (C) 2026 Amalgam Solucoes em TI Ltda
//
// SPDX-License-Identifier: LGPL-2.1-only

package totalcross.ui.image;

import java.util.ArrayList;

import totalcross.ui.MainWindow;

/** One process-wide FIFO. P9 may replace worker wakeup without changing request semantics. */
final class ImagePreparationScheduler {
  enum TerminalState {
    READY,
    STALE,
    NOT_PREFETCHABLE,
    DETERMINISTIC_FAILURE,
    TRANSIENT_FAILURE
  }

  private enum RequestState {
    DISCOVERED,
    QUEUED,
    PREPARING,
    WAITING_ADOPTION,
    READY,
    NOT_PREFETCHABLE,
    STALE,
    DETERMINISTIC_FAILURE,
    TRANSIENT_FAILURE
  }

  private static final Object LOCK = new Object();
  private static final int PENDING_LIMIT = 128;
  private static final int READY_LIMIT = 16;
  private static final ArrayList<Work> QUEUE = new ArrayList<Work>();
  private static final ArrayList<Work> PENDING = new ArrayList<Work>();
  private static final ArrayList<ReadyEntry> READY = new ArrayList<ReadyEntry>();
  private static Work active;

  private ImagePreparationScheduler() {
  }

  static void submit(ImagePreparationRequest request, Runnable completion) {
    if (request == null) {
      ImagePreparationDiagnostics.notPrefetchable();
      completion.run();
      return;
    }
    if (request.source.decodeFailure() != null) {
      ImagePreparationDiagnostics.deterministicFailure();
      completion.run();
      return;
    }
    if (request.target.isDisplayPreparationReady(request)) {
      ImagePreparationDiagnostics.deduplicated();
      ImagePreparationDiagnostics.ready();
      completion.run();
      return;
    }

    Work toStart = null;
    ReadyEntry cached = null;
    boolean attachedToPending = false;
    Work queued = null;
    boolean queueFull = false;
    synchronized (LOCK) {
      for (Work pending : PENDING) {
        if (pending.request.equivalentTo(request)) {
          pending.completions.add(completion);
          attachedToPending = true;
          break;
        }
      }
      if (!attachedToPending) {
        cached = findReadyLocked(request);
      }
      if (!attachedToPending && cached == null) {
        queued = enqueueLocked(request, completion);
        if (queued == null) {
          queueFull = true;
        } else if (active == null) {
          toStart = activateNextLocked();
        }
      }
    }
    updateQueueGauges();
    if (attachedToPending) {
      ImagePreparationDiagnostics.deduplicated();
      return;
    }
    if (cached != null) {
      if (request.target.adoptCachedDisplayPreparation(request, cached.variant, cached.sourceGeneration)) {
        synchronized (LOCK) {
          touchReadyLocked(cached);
        }
        ImagePreparationDiagnostics.deduplicated();
        ImagePreparationDiagnostics.ready();
        completion.run();
        return;
      }
      synchronized (LOCK) {
        READY.remove(cached);
        for (Work pending : PENDING) {
          if (pending.request.equivalentTo(request)) {
            pending.completions.add(completion);
            attachedToPending = true;
            break;
          }
        }
        if (!attachedToPending) {
          queued = enqueueLocked(request, completion);
          if (queued == null) {
            queueFull = true;
          } else if (active == null) {
            toStart = activateNextLocked();
          }
        }
      }
      updateQueueGauges();
      if (attachedToPending) {
        ImagePreparationDiagnostics.deduplicated();
        return;
      }
    }
    if (queueFull) {
      ImagePreparationDiagnostics.transientFailure();
      completion.run();
      return;
    }
    if (queued != null) {
      ImagePreparationDiagnostics.enqueued();
    }
    if (toStart != null) {
      start(toStart);
    }
  }

  private static Work enqueueLocked(ImagePreparationRequest request, Runnable completion) {
    if (PENDING.size() >= PENDING_LIMIT) {
      return null;
    }
    Work work = new Work(request, completion);
    work.state = RequestState.QUEUED;
    PENDING.add(work);
    QUEUE.add(work);
    return work;
  }

  private static ReadyEntry findReadyLocked(ImagePreparationRequest request) {
    long generation = request.source.decodedGeneration();
    for (int i = READY.size() - 1; i >= 0; i--) {
      ReadyEntry entry = READY.get(i);
      if (entry.sourceGeneration == generation && entry.request.equivalentExceptSourceGeneration(request)) {
        return entry;
      }
    }
    return null;
  }

  private static void touchReadyLocked(ReadyEntry entry) {
    READY.remove(entry);
    READY.add(entry);
  }

  private static Work activateNextLocked() {
    if (QUEUE.isEmpty()) {
      active = null;
      return null;
    }
    active = QUEUE.remove(0);
    active.state = RequestState.PREPARING;
    return active;
  }

  static int activeCountForTest() {
    synchronized (LOCK) {
      return active == null ? 0 : 1;
    }
  }

  static int queueDepthForTest() {
    synchronized (LOCK) {
      return QUEUE.size();
    }
  }

  static int pendingCountForTest() {
    synchronized (LOCK) {
      return PENDING.size();
    }
  }

  static int pendingLimitForTest() {
    return PENDING_LIMIT;
  }

  static boolean idleForTest() {
    synchronized (LOCK) {
      return active == null && QUEUE.isEmpty() && PENDING.isEmpty();
    }
  }

  private static void updateQueueGauges() {
    int depth;
    int activeCount;
    synchronized (LOCK) {
      depth = QUEUE.size();
      activeCount = active == null ? 0 : 1;
    }
    ImagePreparationDiagnostics.queueState(depth, activeCount);
  }

  private static void start(final Work work) {
    try {
      new Thread(new Runnable() {
        @Override
        public void run() {
          PreparedImageResult result = work.request.prototype.prepareDetachedForDisplay(work.request);
          synchronized (LOCK) {
            work.state = RequestState.WAITING_ADOPTION;
          }
          MainWindow mainWindow = MainWindow.getMainWindow();
          if (mainWindow == null) {
            abandonWithoutUi(work, result);
            return;
          }
          mainWindow.runOnMainThread(new Runnable() {
            @Override
            public void run() {
              finishOnUi(work, result);
            }
          }, false);
        }
      }).start();
    } catch (RuntimeException | OutOfMemoryError startFailure) {
      MainWindow mainWindow = MainWindow.getMainWindow();
      if (mainWindow == null) {
        finishOnUi(work, PreparedImageResult.transientFailure(null));
      } else {
        mainWindow.runOnMainThread(new Runnable() {
          @Override
          public void run() {
            finishOnUi(work, PreparedImageResult.transientFailure(null));
          }
        }, false);
      }
    }
  }

  private static void abandonWithoutUi(Work work, PreparedImageResult result) {
    result.releaseDetachedEncodedSource();
    synchronized (LOCK) {
      PENDING.remove(work);
      if (active == work) {
        active = null;
      }
      QUEUE.clear();
      PENDING.clear();
      READY.clear();
    }
    updateQueueGauges();
  }

  private static void finishOnUi(Work work, PreparedImageResult result) {
    TerminalState terminal;
    try {
      terminal = work.request.target.adoptPreparedForDisplay(work.request, result);
    } catch (OutOfMemoryError allocationFailure) {
      terminal = TerminalState.TRANSIENT_FAILURE;
    } catch (RuntimeException adoptionFailure) {
      terminal = TerminalState.TRANSIENT_FAILURE;
    }
    ArrayList<Runnable> completions;
    Work next;
    Image variant = terminal == TerminalState.READY
        ? work.request.target.displayPreparationVariant(work.request) : null;
    long sourceGeneration = work.request.source.decodedGeneration();
    synchronized (LOCK) {
      work.state = stateFor(terminal);
      PENDING.remove(work);
      if (terminal == TerminalState.READY && variant != null) {
        READY.add(new ReadyEntry(work.request, sourceGeneration, variant));
        while (READY.size() > READY_LIMIT) {
          READY.remove(0);
        }
      }
      if (active == work) {
        active = null;
      }
      completions = new ArrayList<Runnable>(work.completions);
      next = activateNextLocked();
    }
    recordTerminal(terminal);
    updateQueueGauges();
    result.releaseDetachedEncodedSource();
    invokeCompletions(completions, next);
  }

  private static void recordTerminal(TerminalState state) {
    switch (state) {
    case READY: ImagePreparationDiagnostics.ready(); break;
    case STALE: ImagePreparationDiagnostics.stale(); break;
    case DETERMINISTIC_FAILURE: ImagePreparationDiagnostics.deterministicFailure(); break;
    case TRANSIENT_FAILURE: ImagePreparationDiagnostics.transientFailure(); break;
    default: break;
    }
  }

  private static RequestState stateFor(TerminalState state) {
    switch (state) {
    case READY: return RequestState.READY;
    case STALE: return RequestState.STALE;
    case NOT_PREFETCHABLE: return RequestState.NOT_PREFETCHABLE;
    case DETERMINISTIC_FAILURE: return RequestState.DETERMINISTIC_FAILURE;
    default: return RequestState.TRANSIENT_FAILURE;
    }
  }

  private static void invokeCompletions(ArrayList<Runnable> completions, Work next) {
    Throwable firstFailure = null;
    try {
      for (Runnable completion : completions) {
        try {
          completion.run();
        } catch (Throwable failure) {
          if (firstFailure == null) {
            firstFailure = failure;
          }
        }
      }
    } finally {
      if (next != null) {
        start(next);
      }
    }
    if (firstFailure instanceof Error) {
      throw (Error) firstFailure;
    }
    if (firstFailure instanceof RuntimeException) {
      throw (RuntimeException) firstFailure;
    }
  }

  private static final class Work {
    final ImagePreparationRequest request;
    final ArrayList<Runnable> completions = new ArrayList<Runnable>();
    RequestState state = RequestState.DISCOVERED;

    Work(ImagePreparationRequest request, Runnable completion) {
      this.request = request;
      completions.add(completion);
    }
  }

  private static final class ReadyEntry {
    final ImagePreparationRequest request;
    final long sourceGeneration;
    final Image variant;

    ReadyEntry(ImagePreparationRequest request, long sourceGeneration, Image variant) {
      this.request = request;
      this.sourceGeneration = sourceGeneration;
      this.variant = variant;
    }
  }
}
