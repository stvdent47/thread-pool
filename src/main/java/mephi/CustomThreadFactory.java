package mephi;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class CustomThreadFactory implements ThreadFactory {
	private final Logger logger = Logger.getLogger(CustomThreadFactory.class.getName());

	private final AtomicInteger threadNumber = new AtomicInteger(1);
	private final String namePrefix;

	public CustomThreadFactory(String poolName) {
		this.namePrefix = poolName + "-thread-";
	}

	@Override
	public Thread newThread(Runnable r) {
		String threadName = this.namePrefix + threadNumber.getAndIncrement();
		Thread thread = new Thread(r, threadName);
		thread.setDaemon(false);

		logger.info(
			String.format("[CustomThreadFactory]: creating a new thread '%s'", threadName)
		);

		return thread;
	}
}
