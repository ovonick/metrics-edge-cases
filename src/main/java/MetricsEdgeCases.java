import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.omg.CORBA.TIMEOUT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 * Created by ovonick on 4/15/2014.
 *
 */
public class MetricsEdgeCases {

    private static final Logger                   LOG                       = LoggerFactory.getLogger(MetricsEdgeCases.class);
    private static final MetricRegistry           REGISTRY                  = new MetricRegistry();
    private static final Lock                     LOCK                      = new Lock();
    private static final int                      MAX_DATA_POINTS_COUNT     = 10000;
    private static final int                      SCENARIO_RUNTIME_HOURS    = 3;
    private static final ScheduledExecutorService REPORTER_EXECUTOR_SERVICE = Executors.newScheduledThreadPool(1);
    private static       long[]                   realValuesTimeFrame;
    private static       int                      timeFrameCounter;
    private static       String                   scenarioName;
    private static       int                      scenarioScale;


    public static void main(String... args) throws InterruptedException {
        final String scenarioCommandLineParameter = args == null || args.length < 1 ? null : args[0];

        startReporter();

        if (scenarioCommandLineParameter == null || "1".contains(scenarioCommandLineParameter)) {
            runScenario("scenario1", 20, 1000000, new Scenario1Emulator());
        }
        if (scenarioCommandLineParameter == null || "2".contains(scenarioCommandLineParameter)) {
            runScenario("scenario2", 30, 1000000, new Scenario2Emulator());
        }
        if (scenarioCommandLineParameter == null || "3".contains(scenarioCommandLineParameter)) {
            runScenario("scenario3", 1, 1, new Scenario3Emulator());
        }

        stopReporter();
    }

    private static void stopReporter() {
        REPORTER_EXECUTOR_SERVICE.shutdown();
    }

    private static void startReporter() {
        REPORTER_EXECUTOR_SERVICE.scheduleAtFixedRate(new Reporter(), 1, 1, TimeUnit.MINUTES);
    }

    private static void runScenario(String scenarioName, int runPeriodicityMinutes, int scenarioScale, Runnable scenarioEmulator) throws InterruptedException {
        initializeRealValuesTimeFrame();

        try(Lock ignored = LOCK.lock()) {
            MetricsEdgeCases.scenarioName  = scenarioName;
            MetricsEdgeCases.scenarioScale = scenarioScale;
        }

        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
        executorService.scheduleAtFixedRate(scenarioEmulator, 0, runPeriodicityMinutes, TimeUnit.MINUTES);

        sleep(TimeUnit.HOURS.toMillis(SCENARIO_RUNTIME_HOURS));
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

            while (totalCount < getMaxIterationCount()) {

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

    private static class Scenario3Emulator implements Runnable {

        @Override
        public void run() {
            Timer metricsTimer = REGISTRY.timer(scenarioName);
            int   totalCount   = 0;
            long  executionTime;

            while (totalCount < PrecalculatedWeibull05DistributionSample.sample.length) {

                // just a random execution time
                executionTime = PrecalculatedWeibull05DistributionSample.sample[totalCount];

                // record execution time using metrics timer
                metricsTimer.update(executionTime, TimeUnit.NANOSECONDS);

                // in parallel we record execution time value in simple array so we can compare it to timer
                try(Lock ignored = LOCK.lock()) {
                    realValuesTimeFrame[timeFrameCounter] = executionTime;
                }

                //sleep(10);

                timeFrameCounter++;
                totalCount++;
            }
        }
    }

    private static class Reporter implements Runnable {
        @Override

        public void run() {
            final double percentile95  = 0.95;
            final double percentile99  = 0.99;
            final double percentile999 = 0.999;

            try(Lock ignored = LOCK.lock()) {
                // getting real percentile95 value
                long[] realValuesReporting = new long[timeFrameCounter];
                System.arraycopy(realValuesTimeFrame, 0, realValuesReporting, 0, timeFrameCounter);
                Arrays.sort(realValuesReporting);
                long realPercentile95  = timeFrameCounter == 0 ? 0 : realValuesReporting[(int)(realValuesReporting.length * percentile95)];
                long realPercentile99  = timeFrameCounter == 0 ? 0 : realValuesReporting[(int)(realValuesReporting.length * percentile99)];
                long realPercentile999 = timeFrameCounter == 0 ? 0 : realValuesReporting[(int)(realValuesReporting.length * percentile999)];

                Timer timer = REGISTRY.timer(scenarioName);

                long timestamp = System.currentTimeMillis() / 1000; // graphite format without milliseconds
                LOG.info("test.ovonick.metricsedgecases.{}.real.count {} {}",           scenarioName, timeFrameCounter, timestamp);
                LOG.info("test.ovonick.metricsedgecases.{}.real.{}percentile {} {}",    scenarioName, (int) (percentile95  * 100),  realPercentile95,  timestamp);
                LOG.info("test.ovonick.metricsedgecases.{}.real.{}percentile {} {}",    scenarioName, (int) (percentile99  * 100),  realPercentile99,  timestamp);
                LOG.info("test.ovonick.metricsedgecases.{}.real.{}percentile {} {}",    scenarioName, (int) (percentile999 * 1000), realPercentile999, timestamp);
                LOG.info("test.ovonick.metricsedgecases.{}.metrics.count {} {}",        scenarioName, (int) (timer.getCount()), timestamp);
                LOG.info("test.ovonick.metricsedgecases.{}.metrics.oneminuterate {} {}",scenarioName, (int) (timer.getOneMinuteRate() * 60), timestamp); // timer always returns rate per second. Since we comparing rate per minute we multiply that by 60 which is not correct and thus to show that this is not the right way to get count per minute.
                LOG.info("test.ovonick.metricsedgecases.{}.metrics.{}percentile {} {}", scenarioName, (int) (percentile95  * 100),  (int) timer.getSnapshot().getValue(percentile95)  / scenarioScale, timestamp); // since timer returns nanoseconds we scale it to milliseconds for scenarios 1 and 2
                LOG.info("test.ovonick.metricsedgecases.{}.metrics.{}percentile {} {}", scenarioName, (int) (percentile99  * 100),  (int) timer.getSnapshot().getValue(percentile99)  / scenarioScale, timestamp); // since timer returns nanoseconds we scale it to milliseconds for scenarios 1 and 2
                LOG.info("test.ovonick.metricsedgecases.{}.metrics.{}percentile {} {}", scenarioName, (int) (percentile999 * 1000), (int) timer.getSnapshot().getValue(percentile999) / scenarioScale, timestamp); // since timer returns nanoseconds we scale it to milliseconds for scenarios 1 and 2
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