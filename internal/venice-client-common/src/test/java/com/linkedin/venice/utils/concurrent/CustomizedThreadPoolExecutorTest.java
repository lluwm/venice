package com.linkedin.venice.utils.concurrent;

import static org.mockito.Mockito.timeout;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.mockito.Mockito;
import org.testng.annotations.Test;


public class CustomizedThreadPoolExecutorTest {
  /**
   * Test that the task is executed when submitted to the executor.
   */
  @Test
  public void testTaskExecution() throws InterruptedException {
    Runnable task = Mockito.mock(Runnable.class);
    CustomizedThreadPoolExecutor executor =
        new CustomizedThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    executor.execute(task);
    // Verify that the task was executed.
    Mockito.verify(task, timeout(1000).times(1)).run();
    executor.shutdownNow();
  }

  /**
   * Test that the task is executed when submitted to the executor.
   */
  @Test
  public void testTaskExecutionWithException() throws InterruptedException {
    Runnable task = Mockito.mock(Runnable.class);
    CustomizedThreadPoolExecutor executor =
        new CustomizedThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    Mockito.doThrow(new RuntimeException()).when(task).run();
    executor.execute(task);
    // Verify that the task was executed.
    Mockito.verify(task, timeout(1000).atLeast(10)).run();
    executor.shutdownNow();
  }

  /**
   * Test that the task is executed when submitted to the executor.
   */
  @Test
  public void testTaskSubmitWithException() throws InterruptedException {
    Runnable task = Mockito.mock(Runnable.class);
    CustomizedThreadPoolExecutor executor =
        new CustomizedThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    Mockito.doThrow(new RuntimeException()).when(task).run();
    executor.submit(task);
    // Verify that the task was executed.
    Mockito.verify(task, timeout(1000).times(1)).run();
    executor.shutdownNow();
  }
}
