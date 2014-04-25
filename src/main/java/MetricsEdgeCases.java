import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ovonick
 * Created by ovonick on 4/15/2014.
 *
 */
public class MetricsEdgeCases {

    private static final Logger         LOG                    = LoggerFactory.getLogger(MetricsEdgeCases.class);
    private static final MetricRegistry REGISTRY               = new MetricRegistry();
    private static final Lock           LOCK                   = new Lock();
    private static final int            MAX_DATA_POINTS_COUNT  = 10000;
    private static final int            SCENARIO_RUNTIME_HOURS = 2;
    private static       long[]         realValuesTimeFrame;
    private static       int            timeFrameCounter;
    private static       String         scenarioName;


    public static void main(String... args) throws InterruptedException {
        startReporter();
        reportRunningThreads();
        runScenario("scenario1", 20, new Scenario1Emulator());
        reportRunningThreads();
        runScenario("scenario2", 30, new Scenario2Emulator());
        reportRunningThreads();
    }

    private static void reportRunningThreads() {
        Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();

        for(Thread thread : stackTraces.keySet()) {
            LOG.info(thread.getName());
        }
    }

    private static void startReporter() {
        ScheduledExecutorService reporterExecutorService = Executors.newScheduledThreadPool(1);
        reporterExecutorService.scheduleAtFixedRate(new Reporter(), 1, 1, TimeUnit.MINUTES);
    }

    private static void runScenario(String scenarioName, int runPeriodicityMinutes, Runnable scenarioEmulator) throws InterruptedException {
        initializeRealValuesTimeFrame();

        try(Lock ignored = LOCK.lock()) {
            MetricsEdgeCases.scenarioName = scenarioName;
        }

        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
        executorService.scheduleAtFixedRate(scenarioEmulator, 0, runPeriodicityMinutes, TimeUnit.MINUTES);

        LOG.info("Running {}", scenarioName);

        sleep(TimeUnit.MINUTES.toMillis(SCENARIO_RUNTIME_HOURS));
        executorService.shutdown();
        executorService.awaitTermination(runPeriodicityMinutes, TimeUnit.MINUTES);
    }

    private static void initializeRealValuesTimeFrame() {
        realValuesTimeFrame = new long[MAX_DATA_POINTS_COUNT];
        timeFrameCounter    = 0;
    }

    private static void fakeExecutionMethod(long delayMilliseconds) {
        sleep(delayMilliseconds);
    }

    private abstract static class ScenarioEmulator implements Runnable {

        public abstract long getExecutionTime(int totalCount);
        public abstract int getMaxIterationCount();

        @Override
        public void run() {
            Timer metricsTimer = REGISTRY.timer(scenarioName);
            int   totalCount   = 0;
            long  executionTime;

            while (totalCount < getMaxIterationCount()) { //500

                // just a random execution time
                executionTime = getExecutionTime(totalCount);

                // record execution time using metrics timer
                try (Timer.Context ignored = metricsTimer.time()) {
                    fakeExecutionMethod(executionTime);
                }

                // in parallel we record execution time value in simple array so we can compare it to timer
                try(Lock ignored = LOCK.lock()) {
                    realValuesTimeFrame[timeFrameCounter] = executionTime;
                }

                timeFrameCounter++;
                totalCount++;
            }
        }
    }

    private static class Scenario1Emulator extends ScenarioEmulator {

        private Random random = new Random();

        @Override
        public long getExecutionTime(int totalCount) {
            return random.nextInt(1000);
        }

        @Override
        public int getMaxIterationCount() {
            return 500;
        }
    }

    private static class Scenario2Emulator extends ScenarioEmulator{

        @Override
        public long getExecutionTime(int totalCount) {
            return totalCount < 9960 ? 30 : 15000;
        }

        @Override
        public int getMaxIterationCount() {
            return MAX_DATA_POINTS_COUNT;
        }
    }

    private static class Reporter implements Runnable {
        @Override

        public void run() {
            final double percentile = 0.95;

            try(Lock ignored = LOCK.lock()) {
                // getting real percentile value
                long[] realValuesReporting = new long[timeFrameCounter];
                System.arraycopy(realValuesTimeFrame, 0, realValuesReporting, 0, timeFrameCounter);
                Arrays.sort(realValuesReporting);
                long realPercentile = timeFrameCounter == 0 ? 0 : realValuesReporting[(int)(realValuesReporting.length * percentile)];

                Timer timer = REGISTRY.timer(scenarioName);

                long timestamp = System.currentTimeMillis() / 1000; // graphite format without milliseconds
                LOG.info("test.ovonick.metricsedgecases.{}.real.count {} {}",          scenarioName, timeFrameCounter, timestamp);
                LOG.info("test.ovonick.metricsedgecases.{}.real.{}percentile {} {}",   scenarioName, (int) (percentile * 100), realPercentile, timestamp);
                LOG.info("test.ovonick.metricsedgecases.{}.yammer.{}percentile {} {}", scenarioName, (int) (percentile * 100), (int) timer.getSnapshot().getValue(percentile) / 1000000, timestamp); // since timer returns nanoseconds we scale it to milliseconds
                LOG.info("test.ovonick.metricsedgecases.{}.yammer.count {} {}",        scenarioName, (int) (timer.getOneMinuteRate() * 60), timestamp); // timer always returns rate per second. Since we comparing rate per minute we multiply that by 60
                MetricsEdgeCases.initializeRealValuesTimeFrame();
            }
        }
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            LOG.error(e.getMessage(), e);
        }
    }

    private static class Lock implements AutoCloseable {

        private ReentrantLock lock = new ReentrantLock();

        public Lock lock() {
            lock.lock();
            return this;
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }
}