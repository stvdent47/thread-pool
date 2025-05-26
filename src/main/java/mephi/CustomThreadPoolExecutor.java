package mephi;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class CustomThreadPoolExecutor implements CustomExecutor {
	private final Logger logger = Logger.getLogger(CustomThreadPoolExecutor.class.getName());

	private final int corePoolSize;
	private final int maxPoolSize;
	private final int minSpareThreads;
	private final long keepAliveTime;
	private final TimeUnit timeUnit;
	private final RejectionPolicy rejectionPolicy;

	private final List<BlockingDeque<TaskWrapper>> taskQueues;
	private final Set<WorkerThread> workers;
	private final CustomThreadFactory threadFactory;
	private final AtomicInteger queueRoundRobin = new AtomicInteger(0);

	private volatile boolean shutdown = false;
	private volatile boolean terminated = false;
	private final ReentrantLock poolLock = new ReentrantLock();

	private final AtomicLong completedTasks = new AtomicLong(0);
	private final AtomicLong rejectedTasks = new AtomicLong(0);

	public CustomThreadPoolExecutor(
		int corePoolSize,
		int maxPoolSize,
		int minSpareThreads,
		long keepAliveTime,
		TimeUnit timeUnit,
		int queueSize,
		String poolName,
		RejectionPolicy rejectionPolicy
	) {
		this.corePoolSize = corePoolSize;
		this.maxPoolSize = maxPoolSize;
		this.minSpareThreads = Math.min(minSpareThreads, corePoolSize);
		this.keepAliveTime = keepAliveTime;
		this.timeUnit = timeUnit;
		this.rejectionPolicy = rejectionPolicy;

		this.threadFactory = new CustomThreadFactory(poolName);

		this.taskQueues = new ArrayList<>();
		for (int i = 0; i < this.maxPoolSize; i++) {
			this.taskQueues.add(new LinkedBlockingDeque<>(queueSize));
		}

		this.workers = new HashSet<>();
		for (int i = 0; i < this.corePoolSize; i++) {
			this.addWorker();
		}

		this.logger.info(
			String.format(
				"[CustomThreadPoolExecutor] Initialised with core=%d, max=%d, minSpare=%d, queueSize=%d",
				this.corePoolSize,
				this.maxPoolSize,
				this.minSpareThreads,
				queueSize
			)
		);
	}

	private void reject(TaskWrapper task, String reason) {
		this.rejectedTasks.incrementAndGet();

		this.logger.warning(
			String.format(
				"[Rejected] Task %s was rejected due to: %s",
				task,
				reason
			)
		);

		switch (rejectionPolicy) {
			case ABORT: {
				throw new RejectedExecutionException(
					String.format("Task %s rejected: %s", task, reason)
				);
			}
			case CALLER_RUNS: {
				if (!this.shutdown) {
					this.logger.info(
						String.format(
							"[Rejected] Executing %s in caller thread",
							task
						)
					);

					task.getTask().run();

					break;
				}
			}
			case DISCARD: {
				break;
			}
			case DISCARD_OLDEST: {
				TaskWrapper oldest = null;
				BlockingDeque<TaskWrapper> oldTaskQueue = null;

				for (BlockingDeque<TaskWrapper> taskQueue : this.taskQueues) {
					TaskWrapper head = taskQueue.peek();
					if (head != null && (oldest == null || head.getSubmitTime() < oldest.getSubmitTime())) {
						oldest = head;
						oldTaskQueue = taskQueue;
					}
				}

				if (oldTaskQueue != null && oldTaskQueue.remove(oldest)) {
					this.logger.info(
						String.format(
							"[Rejected] Discarded oldest task %s for %s",
							oldest,
							task
						)
					);
					oldTaskQueue.offer(task);
				}
				break;
			}
			default: {
				break;
			}
		}
	}

	private void checkAndAddWorkerIfNeeded() {
		this.poolLock.lock();

		try {
			int currentWorkerCount = this.workers.size();
			int freeThreads = this.getFreeThreadsCount();

			if (freeThreads < this.minSpareThreads && currentWorkerCount < this.maxPoolSize) {
				this.addWorker();
			}
		}
		finally {
			this.poolLock.unlock();
		}
	}

	@Override
	public void execute(Runnable command) {
		if (command == null) {
			throw new NullPointerException("Command cannot be null");
		}

		if (this.shutdown) {
			this.reject(new TaskWrapper(command), "Pool is shutting down");
			return;
		}

		TaskWrapper taskWrapper = new TaskWrapper(command);

		if (this.offerTask(taskWrapper)) {
			this.checkAndAddWorkerIfNeeded();
		}
		else {
			if (!tryAddWorkerAndExecute(taskWrapper)) {
				this.reject(taskWrapper, "All queues are full and max pool size reached");
			}
		}
	}

	@Override
	public <T> Future<T> submit(Callable<T> callable) {
		if (callable == null) {
			throw new NullPointerException("Callable cannot be null");
		}

		FutureTask<T> futureTask = new FutureTask<>(callable);
		this.execute(futureTask);

		return futureTask;
	}

	@Override
	public void shutdown() {
		this.poolLock.lock();

		try {
			if (this.shutdown) {
				return;
			}

			this.shutdown = true;
			this.logger.info("[CustomThreadPoolExecutor] Shutdown initiated");

			for (WorkerThread worker : this.workers) {
				worker.shutdown();
			}
		}
		finally {
			this.poolLock.unlock();
		}
	}

	@Override
	public void shutdownNow() {
		this.poolLock.lock();

		try {
			this.shutdown();

			int drainedTasks = 0;

			for (BlockingDeque<TaskWrapper> taskQueue : this.taskQueues) {
				drainedTasks += taskQueue.size();
				taskQueue.clear();
			}

			this.logger.info(
				String.format("[CustomThreadPoolExecutor] Shutdown initiated, %d tasks drained", drainedTasks)
			);
		}
		finally {
			this.poolLock.unlock();
		}
	}

	private boolean offerTask(TaskWrapper taskWrapper) {
		int startQueue = this.queueRoundRobin.getAndIncrement() % this.taskQueues.size();

		if (this.taskQueues.get(startQueue).offer(taskWrapper)) {
			this.logger.info(
				String.format(
					"[CustomThreadPoolExecutor] Task accepted into queue #%d: %s",
					startQueue,
					taskWrapper
				)
			);
			return true;
		}

		for (int i = 0; i < this.taskQueues.size(); i++) {
			int queueIndex = (startQueue + i + 1) % this.taskQueues.size();
			if (this.taskQueues.get(queueIndex).offer(taskWrapper)) {
				this.logger.info(
					String.format(
						"[CustomThreadPoolExecutor] Task accepted into queue #%d: %s",
						queueIndex,
						taskWrapper
					)
				);
				return true;
			}
		}

		return false;
	}

	private int findLeastLoadedQueue() {
		int bestQueueIndex = 0;
		int minSize = this.taskQueues.getFirst().size();

		for (int i = 0; i < this.taskQueues.size(); i++) {
			int queueSize = this.taskQueues.get(i).size();

			if (queueSize < minSize) {
				minSize = queueSize;
				bestQueueIndex = i;
			}
		}

		return bestQueueIndex;
	}

	private void addWorker() {
		int queueIndex = this.workers.size();
		if (queueIndex >= this.taskQueues.size()) {
			queueIndex = queueIndex % this.taskQueues.size();
		}

		WorkerThread worker = new WorkerThread(
			this.taskQueues.get(queueIndex),
			this,
			this.keepAliveTime,
			this.timeUnit
		);

		worker.setName(this.threadFactory.newThread(worker).getName());

		this.workers.add(worker);
		worker.start();
	}

	private boolean tryAddWorkerAndExecute(TaskWrapper taskWrapper) {
		this.poolLock.lock();

		try {
			if (this.workers.size() < this.maxPoolSize) {
				int bestQueue = this.findLeastLoadedQueue();
				if (this.taskQueues.get(bestQueue).offer(taskWrapper)) {
					this.addWorker();

					this.logger.info(
						String.format(
							"[CustomThreadPoolExecutor] Created new worker for task %s in queue #%d",
							taskWrapper,
							bestQueue
						)
					);

					return true;
				}
			}

			return false;
		}
		finally {
			this.poolLock.unlock();
		}
	}

	private int getFreeThreadsCount() {
		int busyThreads = 0;

		for (BlockingDeque<TaskWrapper> taskQueue : this.taskQueues) {
			if (!taskQueue.isEmpty()) {
				busyThreads++;
			}
		}

		return Math.max(0, this.workers.size() - busyThreads);
	}

	public boolean canTerminateWorker() {
		this.poolLock.lock();
		try {
			return !this.shutdown && this.workers.size() > this.corePoolSize;
		}
		finally {
			this.poolLock.unlock();
		}
	}

	public void workerTerminated(WorkerThread worker) {
		this.poolLock.lock();

		try {
			this.workers.remove(worker);

			if (this.shutdown && this.workers.isEmpty()) {
				this.terminated = true;
				this.logger.info("[CustomThreadPoolExecutor] All workers are terminated, pool is now terminated");
			}
		}
		finally {
			this.poolLock.unlock();
		}
	}

	public boolean isShutdown() {
		return this.shutdown;
	}

	public boolean isTerminated() {
		return this.terminated;
	}

	public int getActiveCount() {
		return this.workers.size();
	}

	public long getCompletedTaskCount() {
		return this.completedTasks.get();
	}

	public long getRejectedTaskCount() {
		return this.rejectedTasks.get();
	}

	public int getTotalQueueSize() {
		return this.taskQueues.stream().mapToInt(Queue::size).sum();
	}
}
