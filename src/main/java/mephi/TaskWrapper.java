package mephi;

import java.util.concurrent.atomic.AtomicLong;

public class TaskWrapper {
	private static final AtomicLong taskIdGenerator = new AtomicLong(0);

	private final long taskId;
	private final Runnable task;
	private final String description;
	private final long submitTime;

	public TaskWrapper(Runnable task) {
		this.taskId = taskIdGenerator.incrementAndGet();
		this.task = task;
		this.description = task.getClass().getSimpleName() + "@" + System.identityHashCode(task);
		this.submitTime = System.currentTimeMillis();
	}

	public long getTaskId() {
		return this.taskId;
	}

	public Runnable getTask() {
		return this.task;
	}

	public String getDescription() {
		return this.description;
	}

	public long getSubmitTime() {
		return this.submitTime;
	}

	@Override
	public String toString() {
		return String.format("Task-%d(%s)", taskId, description);
	}
}
