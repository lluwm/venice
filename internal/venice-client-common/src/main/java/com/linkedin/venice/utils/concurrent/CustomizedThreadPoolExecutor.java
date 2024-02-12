package com.linkedin.venice.utils.concurrent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public final class CustomizedThreadPoolExecutor extends ThreadPoolExecutor {
  private static final Logger LOGGER = LogManager.getLogger(CustomizedThreadPoolExecutor.class);

  /**
   * Creates a new {@code ThreadPoolExecutor} with the given initial parameters and default thread factory and rejected
   * execution handler. It may be more convenient to use one of the {@link Executors} factory methods instead of this
   * general purpose constructor.
   *
   * @param corePoolSize    the number of threads to keep in the pool, even if they are idle, unless
   *                        {@code allowCoreThreadTimeOut} is set
   * @param maximumPoolSize the maximum number of threads to allow in the pool
   * @param keepAliveTime   when the number of threads is greater than the core, this is the maximum time that excess idle
   *                        threads will wait for new tasks before terminating.
   * @param unit            the time unit for the {@code keepAliveTime} argument
   * @param workQueue       the queue to use for holding tasks before they are executed.  This queue will hold only the
   *                        {@code Runnable} tasks submitted by the {@code execute} method.
   * @throws IllegalArgumentException if one of the following holds:<br> {@code corePoolSize < 0}<br>
   *                                  {@code keepAliveTime < 0}<br> {@code maximumPoolSize <= 0}<br>
   *                                  {@code maximumPoolSize < corePoolSize}
   * @throws NullPointerException     if {@code workQueue} is null
   */
  public CustomizedThreadPoolExecutor(
      int corePoolSize,
      int maximumPoolSize,
      long keepAliveTime,
      TimeUnit unit,
      BlockingQueue<Runnable> workQueue) {
    super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
  }

  public CustomizedThreadPoolExecutor(
      int corePoolSize,
      int maximumPoolSize,
      long keepAliveTime,
      TimeUnit unit,
      BlockingQueue<Runnable> workQueue,
      ThreadFactory threadFactory) {
    super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
  }

  @Override
  public void afterExecute(Runnable r, Throwable t) {
    super.afterExecute(r, t);
    // If submit() method is called instead of execute()
    if (t == null && r instanceof Future<?>) {
      try {
        ((Future<?>) r).get();
      } catch (CancellationException e) {
        t = e;
      } catch (ExecutionException e) {
        t = e.getCause();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    if (t == null) {
      return;
    }

    LOGGER.warn("Detected uncaught exception in customized executor:", t);

    /**
     * For future runnable (submit()), the FutureTask is in the completed exceptionally state and once the computation
     * has completed, the computation cannot be restarted or cancelled {@see FutureTask}.
     *
     * For normal runnable (execute()), the runnable is restarted again.
     */
    if (r instanceof Future<?>) {
      LOGGER.warn(String.format("Computed task state: %s, cannot be restarted", r));
      return;
    }

    LOGGER.warn(String.format("Restarting the runnable %s", r));
    try {
      // Restart the runnable again only in execute
      execute(r);
    } catch (Throwable e) {
      LOGGER.error("Failed to restart the runnable", e);
    }
  }
}
