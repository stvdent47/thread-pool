package mephi;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WorkerThread extends Thread {
	private final Logger logger = Logger.getLogger(WorkerThread.class.getName());

	private final BlockingDeque<TaskWrapper> taskQueue;
	private final CustomThreadPoolExecutor pool;
	private final long keepAliveTime;
	private final TimeUnit timeUnit;
	private volatile boolean running = true;

	public WorkerThread(
		BlockingDeque<TaskWrapper> taskQueue,
		CustomThreadPoolExecutor pool,
		long keepAliveTime,
		TimeUnit timeUnit
	) {
		this.taskQueue = taskQueue;
		this.pool = pool;
		this.keepAliveTime = keepAliveTime;
		this.timeUnit = timeUnit;
	}

	@Override
	public void run() {
		try {
			while (this.running && !this.isInterrupted()) {
				if (this.pool.isShutdown()) {
					break;
				}

				TaskWrapper taskWrapper = null;
				try {
					taskWrapper = this.taskQueue.poll(this.keepAliveTime, this.timeUnit);

					if (taskWrapper == null) {
						if (this.pool.canTerminateWorker()) {
							this.logger.info(
								String.format("[Worker] %s idle timeout, stopping", this.getName())
							);
							break;
						}
						continue;
					}

					this.logger.info(
						String.format("[Worker] %s executes %s", this.getName(), taskWrapper)
					);

					taskWrapper.getTask().run();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
				catch (Exception e) {
					this.logger.log(
						Level.SEVERE,
						String.format(
							"[Worker] %s error executing task %s",
							this.getName(),
							taskWrapper
						),
						e
					);
				}
			}
		}
		finally {
			this.running = false;
			this.pool.workerTerminated(this);
			this.logger.info(String.format("[Worker] %s terminated", this.getName()));
		}
	}

	public void shutdown() {
		this.running = false;
		this.interrupt();
	}
}
