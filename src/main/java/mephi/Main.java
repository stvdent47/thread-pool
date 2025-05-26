package mephi;

import java.util.concurrent.TimeUnit;

public class Main {
	private static String getCurrentTime() {
		long now = System.currentTimeMillis();

		return String.format("%tH:%tM:%tS.%tL", now, now, now, now);
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void printSeparator(String title) {
		System.out.println("\n" + "=".repeat(60));
		System.out.println(title);
		System.out.println("=".repeat(60));
	}

	private static void printPoolStats(CustomThreadPoolExecutor executor, String phase) {
		System.out.printf("[Stats-%s] Active: %d, Queue: %d, Completed: %d, Rejected: %d%n",
			phase,
			executor.getActiveCount(),
			executor.getTotalQueueSize(),
			executor.getCompletedTaskCount(),
			executor.getRejectedTaskCount()
		);
	}

	private static void waitForTermination(CustomThreadPoolExecutor executor, String poolName) {
		System.out.println("\nWaiting for " + poolName + " termination");

		long startTime = System.currentTimeMillis();
		while (!executor.isTerminated() && (System.currentTimeMillis() - startTime) < 10000) {
			sleep(500);
		}

		if (executor.isTerminated()) {
			System.out.println(poolName + " terminated successfully");
		}
		else {
			System.out.println(poolName + " termination timeout, forcing shutdown");
			executor.shutdownNow();
		}

		printPoolStats(executor, "Terminated");
	}

	static class SimulationTask implements Runnable {
		private final int taskId;
		private final long executionTimeMs;
		private final String taskType;

		public SimulationTask(int taskId, long executionTimeMs, String taskType) {
			this.taskId = taskId;
			this.executionTimeMs = executionTimeMs;
			this.taskType = taskType;
		}

		@Override
		public void run() {
			String threadName = Thread.currentThread().getName();
			String startTime = getCurrentTime();

			System.out.printf(
				"[%s] %s-Task-%d started in %s%n",
				startTime,
				taskType,
				taskId,
				threadName
			);

			try {
				Thread.sleep(executionTimeMs);
			}
			catch (InterruptedException e) {
				System.out.printf(
					"[%s] %s-Task-%d interrupted in %s%n",
					getCurrentTime(),
					taskType,
					taskId,
					threadName
				);

				Thread.currentThread().interrupt();

				return;
			}

			System.out.printf(
				"[%s] %s-Task-%d completed in %s (took %dms)%n",
				getCurrentTime(),
				taskType,
				taskId,
				threadName,
				executionTimeMs
			);
		}

		@Override
		public String toString() {
			return String.format("%s-Task-%d(%dms)", taskType, taskId, executionTimeMs);
		}
	}

	private static void demonstrateNormalOperation() {
		printSeparator("Scenario 1: Normal operation");

		CustomThreadPoolExecutor executor = new CustomThreadPoolExecutor(
			2,
			4,
			1,
			5000,
			TimeUnit.MILLISECONDS,
			5,
			"NormalPool",
			RejectionPolicy.CALLER_RUNS
		);

		System.out.println("Created pool: core=2, max=4, minSpare=1, queueSize=5");
		printPoolStats(executor, "Init");

		System.out.println("\nSubmitting 8 normal tasks");
		for (int i = 1; i <= 8; i++) {
			executor.execute(new SimulationTask(i, 1500, "Normal"));
			sleep(200);

			if (i % 3 == 0) {
				printPoolStats(executor, "Progress");
			}
		}

		sleep(4000);
		printPoolStats(executor, "Final");

		executor.shutdown();
		waitForTermination(executor, "NormalPool");
	}

	private static void demonstrateOverloadHandling() {
		printSeparator("Scenario 2: Overload handling");

		RejectionPolicy[] policies = {
			RejectionPolicy.CALLER_RUNS,
			RejectionPolicy.DISCARD,
			RejectionPolicy.DISCARD_OLDEST
		};

		for (RejectionPolicy policy : policies) {
			System.out.println("\n--- Testing rejection policy: " + policy + " ---");

			CustomThreadPoolExecutor executor = new CustomThreadPoolExecutor(
				2,
				3,
				0,
				2000,
				TimeUnit.MILLISECONDS,
				2,
				"OverloadPool-" + policy,
				policy
			);

			printPoolStats(executor, "Init");

			System.out.println("Rapidly submitting 15 tasks to create overload");
			for (int i = 1; i <= 15; i++) {
				try {
					executor.execute(new SimulationTask(i, 2000, "Overload"));
					System.out.printf("Submitted task %d%n", i);
				}
				catch (Exception e) {
					System.out.printf("Task %d rejected with exception: %s%n", i, e.getMessage());
				}

				if (i % 5 == 0) {
					printPoolStats(executor, "Overload");
				}

				sleep(50);
			}

			sleep(1000);
			printPoolStats(executor, "Final");

			executor.shutdownNow();
			sleep(500);
		}
	}

	private static void demonstrateShutdown() {
		printSeparator("Scenario 3: Shutdown procedures");

		CustomThreadPoolExecutor executor = new CustomThreadPoolExecutor(
			3,
			5,
			1,
			5000,
			TimeUnit.MILLISECONDS,
			10,
			"ShutdownPool",
			RejectionPolicy.DISCARD
		);

		System.out.println("Submitting long-running tasks");
		for (int i = 1; i <= 8; i++) {
			executor.execute(new SimulationTask(i, 3000, "Long"));
			sleep(100);
		}

		sleep(1000);
		printPoolStats(executor, "Before shutdown");

		System.out.println("\nInitiating graceful shutdown");
		executor.shutdown();

		try {
			executor.execute(new SimulationTask(999, 1000, "After shutdown"));
		}
		catch (Exception e) {
			System.out.println("Task after shutdown was rejected: " + e.getMessage());
		}

		boolean terminated = false;
		for (int i = 0; i < 10; i++) {
			printPoolStats(executor, "Shutting down");
			if (executor.isTerminated()) {
				terminated = true;
				break;
			}
			sleep(1000);
		}

		if (!terminated) {
			System.out.println("Graceful shutdown taking too long, forcing shutdown");
			executor.shutdownNow();
			sleep(2000);
		}

		System.out.println("Final state - Terminated: " + executor.isTerminated());
	}

	public static void main(String[] args) {
		System.out.println("Starting CustomThreadPoolExecutor Demonstration");
		System.out.println("Current time: " + getCurrentTime());

		demonstrateNormalOperation();

		sleep(2000);

		demonstrateOverloadHandling();

		sleep(2000);

		demonstrateShutdown();

		printSeparator("Demonstration completed successfully");
	}
}
