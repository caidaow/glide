package com.bumptech.glide.load.engine;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.util.Pools;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.executor.GlideExecutor;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * A class that manages a load by adding and removing callbacks for for the load and notifying
 * callbacks when the load completes.
 */
class EngineJob<R> implements DecodeJob.Callback<R> {
  private static final EngineResourceFactory DEFAULT_FACTORY = new EngineResourceFactory();
  private static final Handler MAIN_THREAD_HANDLER =
      new Handler(Looper.getMainLooper(), new MainThreadCallback());

  private static final int MSG_COMPLETE = 1;
  private static final int MSG_EXCEPTION = 2;
  // Used when we realize we're cancelled on a background thread in reschedule and can recycle
  // immediately rather than waiting for a result or an error.
  private static final int MSG_CANCELLED = 3;

  private final List<ResourceCallback> cbs = new ArrayList<>(2);
  private final StateVerifier stateVerifier = StateVerifier.Factory.build();
  private final Pools.Pool<EngineJob<?>> pool;
  private final EngineResourceFactory engineResourceFactory;
  private final EngineJobListener listener;
  private final GlideExecutor diskCacheExecutor;
  private final GlideExecutor sourceExecutor;

  private Key key;
  private boolean isCacheable;
  private Resource<?> resource;
  private boolean hasResource;
  private GlideException exception;
  private boolean hasLoadFailed;
  // A put of callbacks that are removed while we're notifying other callbacks of a change in
  // status.
  private List<ResourceCallback> ignoredCallbacks;
  private EngineResource<?> engineResource;
  private DecodeJob<R> decodeJob;

  // Checked primarily on the main thread, but also on other threads in reschedule.
  private volatile boolean isCancelled;

  EngineJob(GlideExecutor diskCacheExecutor, GlideExecutor sourceExecutor,
      EngineJobListener listener, Pools.Pool<EngineJob<?>> pool) {
    this(diskCacheExecutor, sourceExecutor, listener, pool, DEFAULT_FACTORY);
  }

  // Visible for testing.
  EngineJob(GlideExecutor diskCacheExecutor, GlideExecutor sourceExecutor,
      EngineJobListener listener, Pools.Pool<EngineJob<?>> pool,
      EngineResourceFactory engineResourceFactory) {
    this.diskCacheExecutor = diskCacheExecutor;
    this.sourceExecutor = sourceExecutor;
    this.listener = listener;
    this.pool = pool;
    this.engineResourceFactory = engineResourceFactory;
  }

  // Visible for testing.
  EngineJob<R> init(Key key, boolean isCacheable) {
    this.key = key;
    this.isCacheable = isCacheable;
    stateVerifier.setRecycled(false /*isReleased*/);
    return this;
  }

  public void start(DecodeJob<R> decodeJob) {
    this.decodeJob = decodeJob;
    diskCacheExecutor.execute(decodeJob);
  }

  public void addCallback(ResourceCallback cb) {
    Util.assertMainThread();
    stateVerifier.throwIfRecycled();
    if (hasResource) {
      cb.onResourceReady(engineResource);
    } else if (hasLoadFailed) {
      cb.onLoadFailed(exception);
    } else {
      cbs.add(cb);
    }
  }

  public void removeCallback(ResourceCallback cb) {
    Util.assertMainThread();
    stateVerifier.throwIfRecycled();
    if (hasResource || hasLoadFailed) {
      addIgnoredCallback(cb);
    } else {
      cbs.remove(cb);
      if (cbs.isEmpty()) {
        cancel();
      }
    }
  }

  // We cannot remove callbacks while notifying our list of callbacks directly because doing so
  // would cause a ConcurrentModificationException. However, we need to obey the cancellation
  // request such that if notifying a callback early in the callbacks list cancels a callback later
  // in the request list, the cancellation for the later request is still obeyed. Using a put of
  // ignored callbacks allows us to avoid the exception while still meeting the requirement.
  private void addIgnoredCallback(ResourceCallback cb) {
    if (ignoredCallbacks == null) {
      ignoredCallbacks = new ArrayList<>(2);
    }
    if (!ignoredCallbacks.contains(cb)) {
      ignoredCallbacks.add(cb);
    }
  }

  private boolean isInIgnoredCallbacks(ResourceCallback cb) {
    return ignoredCallbacks != null && ignoredCallbacks.contains(cb);
  }

  // Exposed for testing.
  void cancel() {
    if (hasLoadFailed || hasResource || isCancelled) {
      return;
    }

    isCancelled = true;
    decodeJob.cancel();
    boolean isPendingJobRemoved =
        diskCacheExecutor.remove(decodeJob) || sourceExecutor.remove(decodeJob);
    listener.onEngineJobCancelled(this, key);

    if (isPendingJobRemoved) {
      release();
    }
  }

  // Exposed for testing.
  boolean isCancelled() {
    return isCancelled;
  }

  private void handleResultOnMainThread() {
    stateVerifier.throwIfRecycled();
    if (isCancelled) {
      resource.recycle();
      release();
      return;
    } else if (cbs.isEmpty()) {
      throw new IllegalStateException("Received a resource without any callbacks to notify");
    } else if (hasResource) {
      throw new IllegalStateException("Already have resource");
    }
    engineResource = engineResourceFactory.build(resource, isCacheable);
    hasResource = true;

    // Hold on to resource for duration of request so we don't recycle it in the middle of
    // notifying if it synchronously released by one of the callbacks.
    engineResource.acquire();
    listener.onEngineJobComplete(key, engineResource);

    for (ResourceCallback cb : cbs) {
      if (!isInIgnoredCallbacks(cb)) {
        engineResource.acquire();
        cb.onResourceReady(engineResource);
      }
    }
    // Our request is complete, so we can release the resource.
    engineResource.release();

    release();
  }

  private void handleCancelledOnMainThread() {
    stateVerifier.throwIfRecycled();
    if (!isCancelled) {
      throw new IllegalStateException("Not cancelled");
    }
    listener.onEngineJobCancelled(this, key);
    release();
  }

  private void release() {
    Util.assertMainThread();
    stateVerifier.setRecycled(true /*isReleased*/);
    cbs.clear();
    key = null;
    engineResource = null;
    resource = null;
    if (ignoredCallbacks != null) {
      ignoredCallbacks.clear();
    }
    hasLoadFailed = false;
    isCancelled = false;
    hasResource = false;
    decodeJob.release();
    decodeJob = null;
    exception = null;
    pool.release(this);
  }

  @Override
  public void onResourceReady(Resource<R> resource) {
    this.resource = resource;
    MAIN_THREAD_HANDLER.obtainMessage(MSG_COMPLETE, this).sendToTarget();
  }

  @Override
  public void onLoadFailed(GlideException e) {
    this.exception = e;
    MAIN_THREAD_HANDLER.obtainMessage(MSG_EXCEPTION, this).sendToTarget();
  }

  @Override
  public void reschedule(DecodeJob<?> job) {
    if (isCancelled) {
      MAIN_THREAD_HANDLER.obtainMessage(MSG_CANCELLED, this).sendToTarget();
    } else {
      sourceExecutor.execute(job);
    }
  }

  private void handleExceptionOnMainThread() {
    stateVerifier.throwIfRecycled();
    if (isCancelled) {
      release();
      return;
    } else if (cbs.isEmpty()) {
      throw new IllegalStateException("Received an exception without any callbacks to notify");
    } else if (hasLoadFailed) {
      throw new IllegalStateException("Already failed once");
    }
    hasLoadFailed = true;

    listener.onEngineJobComplete(key, null);

    for (ResourceCallback cb : cbs) {
      if (!isInIgnoredCallbacks(cb)) {
        cb.onLoadFailed(exception);
      }
    }

    release();
  }

  // Visible for testing.
  static class EngineResourceFactory {
    public <R> EngineResource<R> build(Resource<R> resource, boolean isMemoryCacheable) {
      return new EngineResource<>(resource, isMemoryCacheable);
    }
  }

  private static class MainThreadCallback implements Handler.Callback {

    @Override
    public boolean handleMessage(Message message) {
      EngineJob job = (EngineJob) message.obj;
      switch (message.what) {
        case MSG_COMPLETE:
          job.handleResultOnMainThread();
          break;
        case MSG_EXCEPTION:
          job.handleExceptionOnMainThread();
          break;
        case MSG_CANCELLED:
          job.handleCancelledOnMainThread();
          break;
        default:
          throw new IllegalStateException("Unrecognized message: " + message.what);
      }
      return true;
    }
  }
}
